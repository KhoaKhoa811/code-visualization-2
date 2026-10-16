# Loaded only by the temporary test copy; real production scripts never source this.
function Invoke-Docker([string]$Arguments, [int]$TimeoutMs = 5000) {
    if ($Arguments -like 'kill --signal KILL *') {
        if ($script:TestCase -eq 'kill-fails') { throw 'Injected kill failure.' }
        if ($script:TestCase -eq 'late-exit') { Start-Sleep -Milliseconds 750 }
        $script:TestKilled = $true
    }
    if ($Arguments -like 'rm --force *' -and $script:TestCase -eq 'remove-fails') {
        throw 'Injected removal failure.'
    }
    $result = Invoke-DockerCore $Arguments $TimeoutMs
    if ($script:TestCase -eq 'false-confirmation' -and $script:TestKilled -and $Arguments -like 'inspect *') {
        $record = @($result | ConvertFrom-Json)
        $record[0].State.Running = $true
        $record[0].State.Pid = 12345
        return ConvertTo-Json -InputObject $record -Depth 30 -Compress
    }
    if ($script:TestCase -eq 'late-cleanup' -and $Arguments -like 'rm --force *') {
        Start-Sleep -Milliseconds 5100
    }
    return $result
}
