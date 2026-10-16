#requires -Version 5.1
# Final fixed-probe gate. Does not accept or execute submitted programs.
$ErrorActionPreference = 'Stop'
foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1') {
    $tokens = $null
    $errors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw "PowerShell syntax failed: $($file.Name)" }
}
function Invoke-Gate([string]$File, [string[]]$Options = @()) {
    $output = & powershell -NoProfile -File (Join-Path $PSScriptRoot $File) @Options | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Gate failed: $File $Options`n$output" }
    Write-Output "PASS gate: $File $Options"
    $output -split '\r?\n' | Where-Object {
        $_ -match '^PASS:' -and $_ -notmatch 'WSL|daemon|Image |inspected container'
    }
}
Invoke-Gate 'test-output-budget.ps1'
Invoke-Gate 'smoke.ps1'
foreach ($mode in @('RuntimeTimeout','ChildTimeout','Cancel','Filesystem','Network',
    'Privileges','Mounts','StorageBytes','StorageInodes','Heap','ContainerMemory',
    'Tasks','CompilationTimeout','RunIsolation')) {
    Invoke-Gate 'smoke.ps1' @("-$mode")
}
Invoke-Gate 'test-output-quota.ps1'
Invoke-Gate 'test-stop-failures.ps1'
Write-Output 'PASS: Part 5 fixed-probe gate; broader isolation and V1 acceptance remain unfinished.'
