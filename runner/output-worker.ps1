# Runs only fixed probe commands. Management diagnostics use the separate CLI helper.
function Stop-OutputContainer([string]$ContainerName, $Process, $Readers) {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $null = Invoke-Docker "kill --signal KILL $ContainerName" 5000
    $remaining = 5000 - [int]$clock.ElapsedMilliseconds
    if ($remaining -le 0) { throw 'Output termination deadline expired.' }
    $state = (Invoke-Docker "inspect $ContainerName" $remaining | ConvertFrom-Json)[0].State
    if ($state.Running -or $state.Status -ne 'exited' -or $state.Pid -ne 0 -or $state.OOMKilled) {
        throw 'Whole-container output termination is unconfirmed.'
    }
    if ($Process) {
        $remaining = 5000 - [int]$clock.ElapsedMilliseconds
        if ($remaining -le 0 -or -not $Process.WaitForExit($remaining)) { throw 'Output attachment remains unresolved.' }
        $remaining = 5000 - [int]$clock.ElapsedMilliseconds
        if ($remaining -le 0 -or -not [Threading.Tasks.Task]::WaitAll($Readers, $remaining)) { throw 'Output readers did not close.' }
    }
    if ($clock.ElapsedMilliseconds -ge 5000) { throw 'Output termination deadline expired.' }
}

function Invoke-OutputWorker($Budget, [string]$ContainerName, [string]$DockerPath, [string]$Command, [int]$TimeoutMs) {
    if ($Budget.Exceeded) { throw 'Output quota already exceeded; worker launch blocked.' }
    $process = New-Object Diagnostics.Process
    $process.StartInfo.FileName = $DockerPath
    $process.StartInfo.Arguments = "--context desktop-linux exec $ContainerName /usr/bin/env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME=/tmp $Command"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $started = $false
    try {
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $started = $process.Start()
        $readers = @($Budget.DrainAsync($process.StandardOutput.BaseStream, $false),
            $Budget.DrainAsync($process.StandardError.BaseStream, $true))
        $reason = $null
        while ($true) {
            if ($Budget.Exceeded) { $reason = 'OUTPUT_LIMIT'; break }
            if ($readers[0].IsFaulted -or $readers[1].IsFaulted) { throw 'Output reader failed.' }
            # EOF is required on both streams, even when the process exits quickly.
            if ($process.HasExited -and $readers[0].IsCompleted -and $readers[1].IsCompleted) {
                $null = [Threading.Tasks.Task]::WaitAll($readers, 0)
                if ($Budget.Exceeded) { $reason = 'OUTPUT_LIMIT'; break }
                if ($clock.ElapsedMilliseconds -ge $TimeoutMs) { $reason = 'DEADLINE'; break }
                return [pscustomobject]@{ Reason = 'EXIT'; ExitCode = $process.ExitCode }
            }
            if ($clock.ElapsedMilliseconds -ge $TimeoutMs) { $reason = 'DEADLINE'; break }
            Start-Sleep -Milliseconds 10
        }
        Stop-OutputContainer $ContainerName $process $readers
        return [pscustomobject]@{ Reason = $reason; ExitCode = $process.ExitCode }
    }
    finally {
        try {
            if ($started -and -not $process.HasExited) { $script:uncertain = $true; $process.Kill() }
        }
        finally { $process.Dispose() }
    }
}
