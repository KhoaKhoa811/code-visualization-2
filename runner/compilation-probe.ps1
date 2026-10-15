# Fixed javac stall and launch gate; not the submitted-source orchestration service.
function Invoke-CompilationTimeoutProbe([string]$ContainerName, [string]$DockerPath, [string]$Fixture) {
    # An argument FIFO with no writer blocks a real javac before source compilation.
    $prepare = "printf %s $Fixture | base64 -d > /work/Main.java; mkfifo /work/compile.args"
    $null = Invoke-Docker ("exec $ContainerName /bin/sh -ec " + '"' + $prepare + '"')
    $process = New-Object Diagnostics.Process
    $process.StartInfo.FileName = $DockerPath
    $process.StartInfo.Arguments = "--context desktop-linux exec $ContainerName /usr/bin/env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME=/tmp javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -encoding UTF-8 -proc:none --release 21 -d /work @/work/compile.args"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $started = $false
    try {
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $started = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $observedCompiler = $false
        while ($clock.ElapsedMilliseconds -lt 9000 -and -not $process.HasExited) {
            $snapshot = Invoke-Docker "top $ContainerName -eo pid,args" 1000
            if ($snapshot -match '(?m)^\s*\d+\s+javac .+@/work/compile.args\s*$') {
                $observedCompiler = $true
                break
            }
            $null = $process.WaitForExit(100)
        }
        $remaining = [Math]::Max(0, 10000 - [int]$clock.ElapsedMilliseconds)
        $exited = $process.WaitForExit($remaining)
        # Latch once. A late exit cannot reopen the runtime launch gate.
        $timedOut = -not $exited -or $clock.ElapsedMilliseconds -ge 10000
        $elapsed = $clock.ElapsedMilliseconds
        if (-not $timedOut) {
            $remaining = [Math]::Max(0, 10000 - [int]$clock.ElapsedMilliseconds)
            $closed = [Threading.Tasks.Task]::WaitAll(@($stdout, $stderr), $remaining)
            $timedOut = -not $closed -or $clock.ElapsedMilliseconds -ge 10000
            $elapsed = $clock.ElapsedMilliseconds
        }
        if (-not $timedOut) {
            if ($process.ExitCode -ne 0) { throw "Compiler rejected the control input; runtime not launched: $($stderr.Result.Trim())" }
            # Positive-control path: only successful compilation before the deadline may run.
            $output = Invoke-Docker "exec $ContainerName /usr/bin/env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME=/tmp java -Xmx128m -Djava.io.tmpdir=/tmp -cp /work Main" 5000
            Write-Output "CONTROL: successful compilation launched runtime: $output"
            throw 'Expected stalled compilation; compiler completed before its deadline.'
        }
        $termination = [Diagnostics.Stopwatch]::StartNew()
        $null = Invoke-Docker "kill --signal KILL $ContainerName" 5000
        $remaining = 5000 - [int]$termination.ElapsedMilliseconds
        if ($remaining -le 0) { throw 'Compiler termination deadline expired.' }
        $state = (Invoke-Docker "inspect $ContainerName" $remaining | ConvertFrom-Json)[0].State
        if ($state.Running -or $state.Status -ne 'exited' -or $state.Pid -ne 0 -or $state.OOMKilled) {
            throw 'Whole-container compiler termination is unconfirmed.'
        }
        $remaining = 5000 - [int]$termination.ElapsedMilliseconds
        if ($remaining -le 0 -or -not $process.WaitForExit($remaining)) { throw 'Compiler attachment remains unresolved.' }
        $remaining = 5000 - [int]$termination.ElapsedMilliseconds
        if ($remaining -le 0 -or -not [Threading.Tasks.Task]::WaitAll(@($stdout, $stderr), $remaining)) { throw 'Compiler output did not close after stop.' }
        if ($termination.ElapsedMilliseconds -ge 5000) { throw 'Compiler termination deadline expired.' }
        if (-not $observedCompiler -or $stdout.Result.Length -ne 0 -or $stderr.Result.Length -ne 0) {
            throw 'Expected a real blocked javac with no diagnostics.'
        }
        Write-Output "PASS: COMPILATION_TIMEOUT latched after $elapsed ms (10000 ms budget); javac observed before stop."
        Write-Output 'PASS: runtime launch gate stayed closed; whole container exited with PID zero and closed compiler attachment/output.'
    }
    finally {
        try {
            if ($started -and -not $process.HasExited) { $script:uncertain = $true; $process.Kill() }
        }
        finally { $process.Dispose() }
    }
}
