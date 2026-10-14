# Called by smoke.ps1; uses its bounded Invoke-Docker function and owned container.
function Invoke-RuntimeTimeoutProbe([string]$ContainerName, [string]$DockerPath, [string]$Fixture, [switch]$WithChild) {
    $className = if ($WithChild) { 'ParentChildTimeout' } else { 'TimeoutLoop' }
    $compile = "printf %s $Fixture | base64 -d > /work/$className.java; " +
        "javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -encoding UTF-8 -proc:none --release 21 -d /work /work/$className.java"
    $null = Invoke-Docker ("exec $ContainerName /usr/bin/env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME=/tmp /bin/sh -ec " + '"' + $compile + '"') 10000
    Write-Output 'PASS: timeout fixture compiled before the runtime deadline starts.'

    $process = New-Object Diagnostics.Process
    $process.StartInfo.FileName = $DockerPath
    $process.StartInfo.Arguments = "--context desktop-linux exec $ContainerName /usr/bin/env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME=/tmp java -Xmx128m -Djava.io.tmpdir=/tmp -cp /work $className"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $started = $false
    try {
        # Includes Docker launch overhead and JVM startup, but never compilation.
        $runtime = [Diagnostics.Stopwatch]::StartNew()
        $started = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $pairEvidence = $null
        if ($WithChild) {
            # Observe real process ancestry; fixture text alone is insufficient.
            while ($runtime.ElapsedMilliseconds -lt 5000 -and -not $process.HasExited) {
                $remaining = 5000 - [int]$runtime.ElapsedMilliseconds
                # Leave the final second for the deadline wait, not another CLI launch.
                if ($remaining -le 1000) { break }
                $snapshot = Invoke-Docker "top $ContainerName -eo pid,ppid,args" 1000
                $rows = @($snapshot -split '\r?\n' | ForEach-Object {
                    $row = [regex]::Match($_, '^\s*(\d+)\s+(\d+)\s+.*\sParentChildTimeout(\s+child)?\s*$')
                    if ($row.Success) {
                        [pscustomobject]@{ Pid = $row.Groups[1].Value; Parent = $row.Groups[2].Value; Child = $row.Groups[3].Success }
                    }
                })
                $parents = @($rows | Where-Object { -not $_.Child })
                $children = @($rows | Where-Object { $_.Child })
                if ($parents.Count -eq 1 -and $children.Count -eq 1 -and
                    $parents[0].Pid -ne $children[0].Pid -and $children[0].Parent -eq $parents[0].Pid -and
                    $runtime.ElapsedMilliseconds -lt 5000) {
                    $pairEvidence = "parent PID $($parents[0].Pid), child PID $($children[0].Pid)"
                    break
                }
                $remaining = [Math]::Max(0, 5000 - [int]$runtime.ElapsedMilliseconds)
                $null = $process.WaitForExit([Math]::Min(100, $remaining))
            }
        }
        $remaining = [Math]::Max(0, 5000 - [int]$runtime.ElapsedMilliseconds)
        if ($process.WaitForExit($remaining) -and $runtime.ElapsedMilliseconds -lt 5000) {
            throw 'Expected an infinite loop; the runtime exited before its deadline.'
        }
        # Latch timeout before any stop call. Later exit cannot count as completion.
        $elapsed = $runtime.ElapsedMilliseconds
        $termination = [Diagnostics.Stopwatch]::StartNew()
        $null = Invoke-Docker "kill --signal KILL $ContainerName" 5000
        $remaining = 5000 - [int]$termination.ElapsedMilliseconds
        if ($remaining -le 0) { throw 'Termination confirmation deadline expired.' }
        $state = (Invoke-Docker "inspect $ContainerName" $remaining | ConvertFrom-Json)[0].State
        if ($state.Running -or $state.Status -ne 'exited' -or $state.Pid -ne 0 -or $state.OOMKilled) {
            throw 'Whole-container timeout termination is not confirmed.'
        }
        $remaining = 5000 - [int]$termination.ElapsedMilliseconds
        if ($remaining -le 0 -or -not $process.WaitForExit($remaining)) {
            throw 'Runtime launch/attachment remains unresolved after stop.'
        }
        $remaining = 5000 - [int]$termination.ElapsedMilliseconds
        if ($remaining -le 0 -or -not [Threading.Tasks.Task]::WaitAll(@($stdout, $stderr), $remaining)) {
            throw 'Runtime output did not close within termination confirmation.'
        }
        if ($termination.ElapsedMilliseconds -ge 5000) { throw 'Termination confirmation deadline expired.' }
        $expected = if ($WithChild) { "parent-started`nchild-started`n" } else { "loop-started`n" }
        if ($stdout.Result -cne $expected -or $stderr.Result.Length -ne 0) {
            throw 'Timeout occurred without the expected clean Java startup evidence.'
        }
        if ($WithChild) {
            if (-not $pairEvidence) { throw 'Parent/child process ancestry was not observed before timeout.' }
            Write-Output "PASS: observed $pairEvidence before timeout; whole-container termination covers both."
        }
        Write-Output "PASS: EXECUTION_TIMEOUT latched after $elapsed ms (5000 ms budget); whole container stopped."
        Write-Output 'PASS: Java startup recorded; container PID is zero and runtime attachment has exited.'
    }
    finally {
        try {
            if ($started -and -not $process.HasExited) {
                $script:uncertain = $true
                $process.Kill()
            }
        }
        finally { $process.Dispose() }
    }
}
