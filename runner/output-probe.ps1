function Invoke-OutputQuotaProbe([string]$ContainerName, [string]$DockerPath, [string]$Fixture, [string]$Case) {
    Add-Type -Path (Join-Path $PSScriptRoot 'OutputBudget.cs')
    . (Join-Path $PSScriptRoot 'output-worker.ps1')
    $budget = [Codeviz.Probes.OutputBudget]::new(1048576)
    $stage = "printf %s $Fixture | base64 -d > /work/OutputFlood.java"
    $null = Invoke-Docker ("exec $ContainerName /bin/sh -ec " + '"' + $stage + '"')
    $compile = 'javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -encoding UTF-8 -proc:none --release 21 -d /work /work/OutputFlood.java'
    if ($Case -eq 'phases') { $compile = '/bin/sh -ec "printf C >&2; ' + $compile + '"' }
    if ($Case -eq 'compile-over') { $compile = '/bin/sh -ec "head -c 1048577 /dev/zero; ' + $compile + '"' }
    $result = Invoke-OutputWorker $budget $ContainerName $DockerPath $compile 10000
    $runtimeLaunched = $false
    if ($result.Reason -eq 'EXIT' -and $result.ExitCode -eq 0) {
        $runtimeLaunched = $true
        $result = Invoke-OutputWorker $budget $ContainerName $DockerPath "java -Xmx128m -Djava.io.tmpdir=/tmp -cp /work OutputFlood $Case" 5000
    }
    $limited = $Case -notin @('below', 'exact')
    $expectedCount = if ($Case -eq 'below') { 1048575 } else { 1048576 }
    if ($budget.Count -ne $expectedCount -or $budget.Exceeded -ne $limited -or
        ($limited -and $result.Reason -ne 'OUTPUT_LIMIT') -or
        (-not $limited -and ($result.Reason -ne 'EXIT' -or $result.ExitCode -ne 0)) -or
        ($runtimeLaunched -eq ($Case -eq 'compile-over'))) {
        throw "Unexpected output outcome for $Case : $($result.Reason), $($budget.Count) bytes."
    }
    # Validate accepted bytes without decoding, including an incomplete UTF-8 suffix.
    $out = $budget.Snapshot($false)
    $err = $budget.Snapshot($true)
    for ($index = 0; $index -lt $out.Length; $index++) {
        $expected = if ($Case -eq 'compile-over') { 0 } elseif ($Case -eq 'multibyte') { @(226, 130, 172)[$index % 3] } else { 65 }
        if ($out[$index] -ne $expected) { throw 'Accepted stdout prefix changed.' }
    }
    for ($index = 0; $index -lt $err.Length; $index++) {
        $expected = if ($Case -eq 'phases') { 67 } else { 66 }
        if ($err[$index] -ne $expected) { throw 'Accepted stderr prefix changed.' }
    }
    if (($Case -eq 'stderr' -and $out.Length -ne 0) -or
        ($Case -eq 'phases' -and $err.Length -ne 1) -or
        ($Case -eq 'combined' -and ($out.Length -eq 0 -or $err.Length -eq 0)) -or
        ($Case -notin @('stderr', 'phases', 'combined') -and $err.Length -ne 0)) { throw 'Stream identities differ.' }
    if (-not $limited) { Stop-OutputContainer $ContainerName $null $null }
    Write-Output "PASS: $Case retained $($budget.Count) raw bytes; stdout=$($out.Length), stderr=$($err.Length); outcome=$($result.Reason)."
    Write-Output 'PASS: accepted stream prefixes preserved; entire container stopped.'
    if ($Case -eq 'compile-over') { Write-Output 'PASS: compile-phase overflow prevented runtime launch.' }
}
