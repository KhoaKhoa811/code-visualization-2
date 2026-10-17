#requires -Version 5.1
# Fault injection in disposable script copies, with separately verified recovery.
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$directory = Join-Path $root ("codeviz-stop-test-" + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path (Join-Path $directory 'fixtures')
$cases = @(
    @{ Name='kill-fails'; Mode='Cancel'; Expected='Injected kill failure.'; Code=1 },
    @{ Name='false-confirmation'; Mode='Cancel'; Expected='Whole-container termination is not confirmed.'; Code=1 },
    @{ Name='remove-fails'; Mode='Cancel'; Expected='Injected removal failure.'; Code=1 },
    @{ Name='late-cleanup'; Mode='Cancel'; Expected='Cleanup deadline expired.'; Code=1 },
    @{ Name='early-completion'; Mode='Cancel'; Expected='runtime exited before its deadline'; Code=1 },
    @{ Name='cancel-first'; Mode='Cancel'; Expected='PASS: RUN_CANCELLED latched'; Code=0 },
    @{ Name='late-exit'; Mode='RuntimeTimeout'; Expected='PASS: EXECUTION_TIMEOUT latched'; Code=0 }
)
try {
    foreach ($file in @('image.json','preflight.ps1','stop-test-hook.ps1')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $directory
    }
    foreach ($file in @('Main.java','ParentChildTimeout.java','TimeoutLoop.java')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot "fixtures/$file") -Destination (Join-Path $directory 'fixtures')
    }
    $smoke = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'smoke.ps1'))
    # Reuse the repository's bounded CLI helper for independent test recovery.
    $start = $smoke.IndexOf('function Invoke-Docker(')
    $end = $smoke.IndexOf('Write-Output "Smoke container: $name"')
    if ($start -lt 0 -or $end -le $start) { throw 'Bounded recovery helper not found.' }
    . ([scriptblock]::Create($smoke.Substring($start, $end - $start)))
    $docker = (Get-Command docker.exe -CommandType Application | Select-Object -First 1).Source
    $script:uncertain = $false
    $timeout = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'timeout-probe.ps1'))
    foreach ($case in $cases) {
        $hook = "`$script:TestCase = '$($case.Name)'`n. (Join-Path `$PSScriptRoot 'stop-test-hook.ps1')`n"
        $copy = $smoke.Replace('function Invoke-Docker(', 'function Invoke-DockerCore(')
        $copy = $copy.Replace('Write-Output "Smoke container: $name"', $hook + 'Write-Output "Smoke container: $name"')
        [IO.File]::WriteAllText((Join-Path $directory 'smoke.ps1'), $copy)
        $copy = $timeout.Replace('        $elapsed = $runtime.ElapsedMilliseconds', '        Write-Output "TEST: latched=$stopReason"' + "`n" + '        $elapsed = $runtime.ElapsedMilliseconds')
        $copy = $copy.Replace('        $expected = if ($WithChild)', '        Write-Output "TEST: attachment-exit=$($process.ExitCode)"' + "`n" + '        $expected = if ($WithChild)')
        [IO.File]::WriteAllText((Join-Path $directory 'timeout-probe.ps1'), $copy)
        foreach ($file in @('ParentChildTimeout.java','TimeoutLoop.java')) {
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot "fixtures/$file") -Destination (Join-Path $directory 'fixtures') -Force
        }
        if ($case.Name -eq 'early-completion') {
            [IO.File]::WriteAllText((Join-Path $directory 'fixtures/ParentChildTimeout.java'), 'public class ParentChildTimeout { public static void main(String[] args) {} }')
        }
        if ($case.Name -eq 'late-exit') {
            [IO.File]::WriteAllText((Join-Path $directory 'fixtures/TimeoutLoop.java'), 'public class TimeoutLoop { public static void main(String[] args) throws Exception { System.out.println("loop-started"); Thread.sleep(5200); } }')
        }
        $output = & powershell -NoProfile -File (Join-Path $directory 'smoke.ps1') "-$($case.Mode)" | Out-String
        $exitCode = $LASTEXITCODE
        # Recover only the GUID-labelled container created by this exact invocation.
        $match = [regex]::Match($output, '(?m)^Smoke container: codeviz-smoke-([0-9a-f]{32})\r?$')
        if (-not $match.Success) { throw "No owned container identity: $output" }
        $label = 'codeviz.smoke=' + $match.Groups[1].Value
        $ids = Invoke-Docker "ps --all --quiet --no-trunc --filter label=$label"
        if ($ids) {
            if ($ids -notmatch '^[0-9a-f]{64}$') { throw 'Unexpected recovery inventory.' }
            $null = Invoke-Docker "rm --force $ids"
        }
        $left = Invoke-Docker "ps --all --quiet --filter label=$label"
        if ($left -or $script:uncertain) { throw 'Recovery absence unconfirmed.' }
        if ($exitCode -ne $case.Code -or -not $output.Contains($case.Expected)) { throw "Case failed: $($case.Name)`n$output" }
        if ($case.Name -in @('kill-fails','false-confirmation','early-completion') -and $output -match 'PASS: RUN_CANCELLED') { throw 'Unconfirmed cancellation reported success.' }
        if ($case.Name -in @('kill-fails','remove-fails','late-cleanup') -and $output -match 'PASS: owned smoke container removed') { throw 'Cleanup failure was hidden.' }
        if ($case.Name -eq 'early-completion' -and $output -match 'TEST: latched=') { throw 'Early completion became a stop cause.' }
        if ($case.Name -eq 'late-exit' -and $output -notmatch 'TEST: attachment-exit=0') { throw "Late successful exit was not observed: $output" }
        if ($case.Code -eq 0 -and $output -notmatch 'PASS: owned smoke container removed') { throw 'Normal cleanup not verified.' }
        Write-Output "PASS: $($case.Name); original outcome checked; independent recovery absence verified."
    }
}
finally {
    $resolved = [IO.Path]::GetFullPath($directory)
    if (-not $resolved.StartsWith($root, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '^codeviz-stop-test-[0-9a-f]{32}$') { throw 'Unsafe test-directory cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
