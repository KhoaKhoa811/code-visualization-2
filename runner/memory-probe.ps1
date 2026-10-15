# Fixed container-memory probe; uses smoke.ps1 ownership, bounded CLI and cleanup.
function Invoke-ContainerMemoryProbe([string]$ContainerName, [string]$DockerPath, [string]$Fixture) {
    function Read-MemoryEvents([int]$TimeoutMs = 1000) {
        $raw = Invoke-Docker "exec $ContainerName /bin/cat /sys/fs/cgroup/memory.events" $TimeoutMs
        $values = @{}
        foreach ($key in @('max', 'oom', 'oom_kill')) {
            $matches = [regex]::Matches($raw, "(?m)^$key ([0-9]+)\r?$")
            if ($matches.Count -ne 1) { throw "Missing or duplicate memory counter: $key" }
            $values[$key] = [long]$matches[0].Groups[1].Value
        }
        return $values
    }
    $limits = Invoke-Docker "exec $ContainerName /bin/cat /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.swap.max"
    if (($limits -replace "`r", '') -cne "536870912`n0") { throw 'Effective memory/swap limits differ from 512 MiB/zero swap.' }
    $compile = "printf %s $Fixture | base64 -d > /work/ContainerMemoryPressure.java; " +
        'javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -encoding UTF-8 -proc:none --release 21 -d /work /work/ContainerMemoryPressure.java'
    $null = Invoke-Docker ("exec $ContainerName /usr/bin/env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME=/tmp /bin/sh -ec " + '"' + $compile + '"') 10000
    $baseline = Read-MemoryEvents
    if ($baseline.oom -ne 0 -or $baseline.oom_kill -ne 0) { throw 'OOM evidence predates the pressure fixture.' }
    Write-Output 'PASS: effective memory.max=536870912 and memory.swap.max=0; compilation finished without OOM.'

    $process = New-Object Diagnostics.Process
    $process.StartInfo.FileName = $DockerPath
    $process.StartInfo.Arguments = "--context desktop-linux exec $ContainerName /usr/bin/env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME=/tmp java -Xmx128m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Djava.io.tmpdir=/tmp -cp /work ContainerMemoryPressure"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $started = $false
    try {
        $runtime = [Diagnostics.Stopwatch]::StartNew()
        $started = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $evidence = $null
        while ($runtime.ElapsedMilliseconds -lt 4000) {
            $observed = Read-MemoryEvents
            if ($runtime.ElapsedMilliseconds -lt 5000 -and $observed.max -gt $baseline.max -and
                $observed.oom -gt $baseline.oom -and $observed.oom_kill -gt $baseline.oom_kill) {
                $evidence = $observed
                break
            }
            Start-Sleep -Milliseconds 100
        }
        if (-not $evidence) {
            $remaining = [Math]::Max(0, 5000 - [int]$runtime.ElapsedMilliseconds)
            Start-Sleep -Milliseconds $remaining
        }
        $elapsed = $runtime.ElapsedMilliseconds
        # Kill the whole environment even if the kernel killed only one worker.
        $termination = [Diagnostics.Stopwatch]::StartNew()
        $state = (Invoke-Docker "inspect $ContainerName" 1000 | ConvertFrom-Json)[0].State
        if ($state.Running) {
            $remaining = 5000 - [int]$termination.ElapsedMilliseconds
            if ($remaining -le 0) { throw 'Memory-probe termination deadline expired.' }
            $null = Invoke-Docker "kill --signal KILL $ContainerName" $remaining
        }
        $remaining = 5000 - [int]$termination.ElapsedMilliseconds
        if ($remaining -le 0) { throw 'Memory-probe termination deadline expired.' }
        $state = (Invoke-Docker "inspect $ContainerName" $remaining | ConvertFrom-Json)[0].State
        if ($state.Running -or $state.Status -ne 'exited' -or $state.Pid -ne 0) { throw 'Whole-container memory-probe termination is unconfirmed.' }
        $remaining = 5000 - [int]$termination.ElapsedMilliseconds
        if ($remaining -le 0 -or -not $process.WaitForExit($remaining)) { throw 'Memory-probe attachment remains unresolved.' }
        $remaining = 5000 - [int]$termination.ElapsedMilliseconds
        if ($remaining -le 0 -or -not [Threading.Tasks.Task]::WaitAll(@($stdout, $stderr), $remaining)) { throw 'Memory-probe output did not close.' }
        if ($termination.ElapsedMilliseconds -ge 5000) { throw 'Memory-probe termination deadline expired.' }
        if (-not $evidence) { throw 'No verified container OOM kill before the runtime deadline.' }
        if ($stdout.Result -cne "pressure-started`n") { throw 'Missing or unexpected fixed-fixture startup output.' }
        if ($stderr.Result.Length -ne 0) { throw "Unexpected Java worker error: $($stderr.Result.Trim())" }
        Write-Output "PASS: MEMORY_LIMIT evidence after $elapsed ms: max=$($evidence.max), oom=$($evidence.oom), oom_kill=$($evidence.oom_kill)."
        Write-Output 'PASS: whole container stopped, PID zero, attachment exited, and output closed.'
    }
    finally {
        try {
            if ($started -and -not $process.HasExited) { $script:uncertain = $true; $process.Kill() }
        }
        finally { $process.Dispose() }
    }
}
