#requires -Version 5.1
# Integration gate: each case owns a fresh restricted container and checks cleanup.
$ErrorActionPreference = 'Continue'
foreach ($case in @('below', 'exact', 'stdout', 'stderr', 'combined', 'multibyte', 'phases', 'compile-over', 'flood')) {
    $output = & powershell -NoProfile -File (Join-Path $PSScriptRoot 'smoke.ps1') -OutputQuota -OutputCase $case 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $output -notmatch 'PASS: owned smoke container removed and absence verified') {
        throw "Output-quota case failed: $case`n$output"
    }
    $output -split '\r?\n' | Where-Object { $_ -match "^PASS: $case retained|^PASS: compile-phase" }
}
Write-Output 'PASS: all nine output cases and owned-container cleanup.'
