#requires -Version 5.1
$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'OutputBudget.cs')
function Feed($Budget, [byte[]]$Bytes, [bool]$ErrorStream = $false) {
    $stream = [IO.MemoryStream]::new($Bytes, $false)
    try { $null = $Budget.DrainAsync($stream, $ErrorStream).GetAwaiter().GetResult() }
    finally { $stream.Dispose() }
}
foreach ($size in @(1048575, 1048576, 1048577)) {
    $budget = [Codeviz.Probes.OutputBudget]::new(1048576)
    Feed $budget ([byte[]]::new($size))
    if ($budget.Count -ne [Math]::Min($size, 1048576) -or $budget.Exceeded -ne ($size -gt 1048576)) {
        throw "Boundary failure: $size"
    }
}
$budget = [Codeviz.Probes.OutputBudget]::new(4)
Feed $budget ([byte[]]@(0xE2, 0x82))
Feed $budget ([byte[]]@(0xAC)) $true
Feed $budget ([byte[]]@(0xFF, 0x00)) $true
if (-not $budget.Exceeded -or $budget.Count -ne 4 -or
    [Convert]::ToBase64String($budget.Snapshot($false)) -ne '4oI=' -or
    [Convert]::ToBase64String($budget.Snapshot($true)) -ne 'rP8=') {
    throw 'Raw split bytes or stream identities were changed.'
}
Feed $budget ([byte[]]@(1, 2, 3))
if ($budget.Count -ne 4 -or -not $budget.Exceeded) { throw 'Quota reset across worker streams.' }
# Two asynchronous pipe readers compete for the final allowance.
$budget = [Codeviz.Probes.OutputBudget]::new(4)
$outPipe = [IO.Pipes.AnonymousPipeServerStream]::new([IO.Pipes.PipeDirection]::Out)
$errPipe = [IO.Pipes.AnonymousPipeServerStream]::new([IO.Pipes.PipeDirection]::Out)
$outRead = [IO.Pipes.AnonymousPipeClientStream]::new([IO.Pipes.PipeDirection]::In, $outPipe.GetClientHandleAsString())
$errRead = [IO.Pipes.AnonymousPipeClientStream]::new([IO.Pipes.PipeDirection]::In, $errPipe.GetClientHandleAsString())
try {
    $readers = @($budget.DrainAsync($outRead, $false), $budget.DrainAsync($errRead, $true))
    $outPipe.Write([byte[]]@(1, 2, 3), 0, 3)
    $errPipe.Write([byte[]]@(4, 5, 6), 0, 3)
    $outPipe.Dispose(); $errPipe.Dispose()
    if (-not [Threading.Tasks.Task]::WaitAll($readers, 3000)) { throw 'Concurrent drain did not finish.' }
    if ($budget.Count -ne 4 -or -not $budget.Exceeded) { throw 'Shared atomic quota failed.' }
} finally {
    $outPipe.Dispose(); $errPipe.Dispose(); $outRead.Dispose(); $errRead.Dispose()
}
Write-Output 'PASS: byte boundaries, split/raw bytes, stream identity, persistent quota, and concurrent readers.'
