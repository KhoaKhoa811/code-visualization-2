function Invoke-RunIsolationProbe([string]$ContainerName, [string]$Label, [string]$Options) {
    $source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'fixtures/run-isolation.sh')).Replace("`r`n", "`n")
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($source))
    $tokens = @(1..3 | ForEach-Object { [guid]::NewGuid().ToString('N') })
    $primary = (Invoke-Docker "inspect $ContainerName" | ConvertFrom-Json)[0]
    $profile = $primary.HostConfig | ConvertTo-Json -Depth 20 -Compress
    function Check-Markers($Target, $Mode, $Own, $Other, $Previous) {
        $command = "printf %s $encoded | base64 -d > /work/run-isolation.sh; /bin/sh /work/run-isolation.sh $Mode $Own $Other $Previous"
        $output = Invoke-Docker ("exec $Target /bin/sh -ec " + '"' + $command + '"')
        if ($output -cne 'run-isolation-ok') { throw 'Unexpected run-isolation fixture output.' }
    }
    Check-Markers $ContainerName seed $tokens[0] $tokens[1] $tokens[2]
    $identity = [guid]::NewGuid().ToString('N')
    $peerName = "codeviz-isolation-$identity"
    $peerLabel = "codeviz.isolation=$identity"
    $peerOptions = $Options.Replace("--name $ContainerName --label $Label ", "--name $peerName --label $peerLabel ")
    if ($peerOptions -eq $Options) { throw 'Peer ownership substitution failed.' }
    $previousId = $null
    foreach ($index in @(1, 2)) {
        try {
            $null = Invoke-Docker ($peerOptions + '"exec sleep infinity"') 10000
            $null = Invoke-Docker "start $peerName"
            $peer = (Invoke-Docker "inspect $peerName" | ConvertFrom-Json)[0]
            if ($peer.Id -eq $primary.Id -or $peer.Id -eq $previousId -or
                ($peer.HostConfig | ConvertTo-Json -Depth 20 -Compress) -cne $profile -or
                $peer.Config.User -ne $primary.Config.User -or $peer.Image -ne $primary.Image) {
                $differences = @($primary.HostConfig.PSObject.Properties.Name | Where-Object {
                    ($primary.HostConfig.$_ | ConvertTo-Json -Depth 20 -Compress) -cne ($peer.HostConfig.$_ | ConvertTo-Json -Depth 20 -Compress)
                }) -join ', '
                throw "Peer identity or restricted profile differs: $differences"
            }
            $previousId = $peer.Id
            $otherIndex = if ($index -eq 1) { 2 } else { 1 }
            Check-Markers $peerName seed $tokens[$index] $tokens[0] $tokens[$otherIndex]
            Check-Markers $ContainerName check $tokens[0] $tokens[1] $tokens[2]
            Check-Markers $peerName check $tokens[$index] $tokens[0] $tokens[$otherIndex]
            Write-Output "PASS: run $index has private markers in /work, /tmp, /dev/shm; original run remains unchanged."
        }
        finally {
            # Keep peer cleanup independent from smoke.ps1's primary ownership label.
            $cleanup = [Diagnostics.Stopwatch]::StartNew()
            try {
                $ids = Invoke-Docker "ps --all --quiet --no-trunc --filter label=$peerLabel"
                if ($ids) {
                    if ($ids -notmatch '^[0-9a-f]{64}$') { throw 'Unexpected peer ownership inventory.' }
                    $remaining = 5000 - [int]$cleanup.ElapsedMilliseconds
                    if ($remaining -le 0) { throw 'Peer cleanup deadline expired.' }
                    $null = Invoke-Docker "rm --force $ids" $remaining
                }
                $remaining = 5000 - [int]$cleanup.ElapsedMilliseconds
                if ($remaining -le 0) { throw 'Peer cleanup deadline expired.' }
                $left = Invoke-Docker "ps --all --quiet --filter label=$peerLabel" $remaining
                if ($left -or $script:uncertain) { throw 'Peer cleanup is unconfirmed.' }
                Write-Output 'PASS: owned peer removed and absence verified.'
            }
            catch { $script:uncertain = $true; throw }
        }
    }
    Check-Markers $ContainerName check $tokens[0] $tokens[1] $tokens[2]
    Write-Output 'PASS: recreated peer has a new container ID and no previous markers; primary survives peer cleanup.'
}
