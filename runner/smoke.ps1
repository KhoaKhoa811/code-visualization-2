#requires -Version 5.1
# Fixed trusted tool commands and repository fixture only; not submitted Java.
[CmdletBinding()]
param([switch]$RuntimeTimeout, [switch]$ChildTimeout, [switch]$Cancel, [switch]$Filesystem, [switch]$Network, [switch]$Privileges, [switch]$Mounts, [switch]$StorageBytes, [switch]$StorageInodes, [switch]$Heap, [switch]$ContainerMemory)

$ErrorActionPreference = 'Stop'
if ($ContainerMemory -and ($Heap -or $StorageInodes -or $StorageBytes -or $Mounts -or $Privileges -or $Network -or $Filesystem -or $RuntimeTimeout -or $ChildTimeout -or $Cancel)) { throw 'Select container memory or another probe.' }
if ($Heap -and ($StorageInodes -or $StorageBytes -or $Mounts -or $Privileges -or $Network -or $Filesystem -or $RuntimeTimeout -or $ChildTimeout -or $Cancel)) { throw 'Select heap or another probe.' }
if ($StorageInodes -and ($StorageBytes -or $Mounts -or $Privileges -or $Network -or $Filesystem -or $RuntimeTimeout -or $ChildTimeout -or $Cancel)) { throw 'Select storage inodes or another probe.' }
if ($StorageBytes -and ($Mounts -or $Privileges -or $Network -or $Filesystem -or $RuntimeTimeout -or $ChildTimeout -or $Cancel)) { throw 'Select storage bytes or another probe.' }
if ($Mounts -and ($Privileges -or $Network -or $Filesystem -or $RuntimeTimeout -or $ChildTimeout -or $Cancel)) { throw 'Select mounts or another probe.' }
if ($Privileges -and ($Network -or $Filesystem -or $RuntimeTimeout -or $ChildTimeout -or $Cancel)) { throw 'Select privileges or another probe.' }
if ($Network -and ($Filesystem -or $RuntimeTimeout -or $ChildTimeout -or $Cancel)) { throw 'Select network or another probe.' }
if ($Filesystem -and ($RuntimeTimeout -or $ChildTimeout -or $Cancel)) { throw 'Select filesystem or a stop probe.' }
if ($RuntimeTimeout -and $ChildTimeout) { throw 'Select only one timeout probe.' }
if ($Cancel -and ($RuntimeTimeout -or $ChildTimeout)) { throw 'Select cancellation or a timeout probe.' }
if ($Cancel) { $ChildTimeout = $true }
if ($ChildTimeout) { $RuntimeTimeout = $true }
$pin = Get-Content (Join-Path $PSScriptRoot 'image.json') -Raw | ConvertFrom-Json
if ($pin.image -notmatch '^eclipse-temurin@sha256:[0-9a-f]{64}$' -or $pin.platform -ne 'linux/amd64') {
    throw 'Expected the reviewed linux/amd64 image pin.'
}
& (Join-Path $PSScriptRoot 'preflight.ps1') -Image $pin.image
if ($LASTEXITCODE -ne 0) { exit 1 }
$fixtureName = if ($ContainerMemory) { 'ContainerMemoryPressure.java' } elseif ($Heap) { 'HeapExhaustion.java' } elseif ($Network) { 'NetworkIsolation.java' } elseif ($ChildTimeout) { 'ParentChildTimeout.java' } elseif ($RuntimeTimeout) { 'TimeoutLoop.java' } else { 'Main.java' }
$fixture = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PSScriptRoot "fixtures/$fixtureName")))
if ($Filesystem -or $Privileges -or $Mounts -or $StorageBytes -or $StorageInodes) {
    # Normalize Windows checkouts before sending the fixed POSIX shell fixture.
    $shellFixture = if ($StorageInodes) { 'storage-inodes.sh' } elseif ($StorageBytes) { 'storage-bytes.sh' } elseif ($Mounts) { 'mounts.sh' } elseif ($Privileges) { 'privileges.sh' } else { 'filesystem.sh' }
    $source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot "fixtures/$shellFixture")).Replace("`r`n", "`n")
    $fixture = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($source))
}
$docker = (Get-Command docker.exe -CommandType Application | Select-Object -First 1).Source
$identity = [guid]::NewGuid().ToString('N')
$name = "codeviz-smoke-$identity"
$label = "codeviz.smoke=$identity"
$failed = $false
$uncertain = $false

function Invoke-Docker([string]$Arguments, [int]$TimeoutMs = 5000) {
    $process = New-Object Diagnostics.Process
    $process.StartInfo.FileName = $docker
    $process.StartInfo.Arguments = "--context desktop-linux $Arguments"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $started = $false
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $started = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMs)) {
            $script:uncertain = $true
            throw 'Docker call timed out; daemon operations may still be pending.'
        }
        $remaining = [Math]::Max(0, $TimeoutMs - [int]$timer.ElapsedMilliseconds)
        if (-not [Threading.Tasks.Task]::WaitAll(@($stdout, $stderr), $remaining)) {
            $script:uncertain = $true
            throw 'Docker output did not close within the deadline.'
        }
        if ($process.ExitCode -ne 0) { throw "Docker exit $($process.ExitCode): $($stderr.Result.Trim())" }
        return $stdout.Result.Trim()
    }
    finally {
        try { if ($started -and -not $process.HasExited) { $process.Kill() } }
        finally { $process.Dispose() }
    }
}

Write-Output "Smoke container: $name"
try {
    $options = "create --pull=never --name $name --label $label --platform $($pin.platform) " +
        '--user 10001:10001 --cap-drop=ALL --security-opt=no-new-privileges=true --read-only ' +
        '--network=none --ipc=private --memory=536870912 --memory-swap=536870912 --pids-limit=128 ' +
        '--restart=no --log-driver=none --ulimit core=0:0 --no-healthcheck --shm-size=16777216 ' +
        '--tmpfs /work:rw,nosuid,nodev,noexec,size=67108864,nr_inodes=4096,uid=10001,gid=10001,mode=700 ' +
        '--tmpfs /tmp:rw,nosuid,nodev,noexec,size=16777216,nr_inodes=1024,uid=10001,gid=10001,mode=700 ' +
        '--tmpfs /dev/shm:rw,nosuid,nodev,noexec,size=16777216,nr_inodes=1024,uid=10001,gid=10001,mode=700 ' +
        "--workdir /work --entrypoint /usr/bin/env $($pin.image) " +
        '-i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME=/tmp /bin/sh -ec '
    $probe = 'id -u; id -g; java -Xmx128m -Djava.io.tmpdir=/tmp -version 2>&1; javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -version 2>&1; ' +
        "printf %s $fixture | base64 -d > /work/Main.java; " +
        'javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -encoding UTF-8 -proc:none --release 21 -d /work /work/Main.java; ' +
        'java -Xmx128m -Djava.io.tmpdir=/tmp -cp /work Main > /work/actual.txt 2> /work/program.err; ' +
        "printf 'runner-ok\n' > /work/expected.txt; " +
        'cmp /work/expected.txt /work/actual.txt; test ! -s /work/program.err; cat /work/actual.txt'
    if ($RuntimeTimeout -or $ContainerMemory) { $probe = 'exec sleep infinity' }
    if ($Filesystem) { $probe = "printf %s $fixture | base64 -d > /work/filesystem.sh; /bin/sh /work/filesystem.sh" }
    if ($Privileges) { $probe = "printf %s $fixture | base64 -d > /work/privileges.sh; /bin/sh /work/privileges.sh" }
    if ($Mounts) { $probe = "printf %s $fixture | base64 -d > /work/mounts.sh; /bin/sh /work/mounts.sh" }
    if ($StorageBytes) { $probe = "printf %s $fixture | base64 -d > /work/storage-bytes.sh; /bin/sh /work/storage-bytes.sh" }
    if ($StorageInodes) { $probe = "printf %s $fixture | base64 -d > /work/storage-inodes.sh; /bin/sh /work/storage-inodes.sh" }
    if ($Network) {
        $probe = "printf %s $fixture | base64 -d > /work/NetworkIsolation.java; " +
            'javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -encoding UTF-8 -proc:none --release 21 -d /work /work/NetworkIsolation.java; ' +
            'java -Xmx128m -Djava.io.tmpdir=/tmp -cp /work NetworkIsolation'
    }
    if ($Heap) {
        $checks = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'fixtures/heap-check.sh')).Replace("`r`n", "`n")
        $encodedChecks = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($checks))
        $probe = "printf %s $fixture | base64 -d > /work/HeapExhaustion.java; " +
            'javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -encoding UTF-8 -proc:none --release 21 -d /work /work/HeapExhaustion.java; ' +
            "printf %s $encodedChecks | base64 -d > /work/heap-check.sh; /bin/sh /work/heap-check.sh"
    }
    $null = Invoke-Docker ($options + '"' + $probe + '"') 10000
    $container = (Invoke-Docker "inspect $name" | ConvertFrom-Json)[0]
    $hostConfig = $container.HostConfig
    if ($container.Config.User -ne '10001:10001' -or -not $hostConfig.ReadonlyRootfs -or
        $hostConfig.NetworkMode -ne 'none' -or $hostConfig.Memory -ne 536870912 -or
        $hostConfig.MemorySwap -ne 536870912 -or $hostConfig.PidsLimit -ne 128 -or
        $hostConfig.Privileged -or $hostConfig.CapDrop -notcontains 'ALL' -or
        $hostConfig.SecurityOpt -notcontains 'no-new-privileges=true' -or
        $hostConfig.LogConfig.Type -ne 'none' -or $hostConfig.RestartPolicy.Name -ne 'no' -or
        @($container.Mounts | Where-Object { $_.Type -ne 'tmpfs' }).Count -ne 0) {
        throw 'Created container configuration differs from the restricted smoke profile.'
    }
    Write-Output 'PASS: inspected container user, resource settings, network, capabilities, rootfs, logs, and mounts.'
    if ($Mounts) {
        $destinations = @($hostConfig.Tmpfs.PSObject.Properties.Name | Sort-Object)
        if (($destinations -join ',') -ne '/dev/shm,/tmp,/work' -or
            @($container.Mounts | Where-Object { $_.Destination -notin @('/dev/shm', '/tmp', '/work') }).Count -gt 0 -or
            @($hostConfig.Binds | Where-Object { $null -ne $_ }).Count -gt 0 -or
            @($hostConfig.VolumesFrom | Where-Object { $null -ne $_ }).Count -gt 0 -or
            @($hostConfig.Devices | Where-Object { $null -ne $_ }).Count -gt 0 -or
            @($hostConfig.DeviceRequests | Where-Object { $null -ne $_ }).Count -gt 0) {
            throw 'Mount audit requires only the three approved tmpfs mounts and no host devices or volumes.'
        }
    }
    if ($Privileges -and ((@($hostConfig.GroupAdd | Where-Object { $null -ne $_ }).Count -gt 0) -or
        (@($hostConfig.CapAdd | Where-Object { $null -ne $_ }).Count -gt 0) -or
        @($hostConfig.SecurityOpt | Where-Object { $_ -ne 'no-new-privileges=true' }).Count -gt 0)) {
        throw 'Privilege probe must retain no extra groups/capabilities and the default seccomp selection.'
    }
    if ($Network -and ($hostConfig.PublishAllPorts -or @($hostConfig.PortBindings.PSObject.Properties).Count -gt 0)) {
        throw 'Network probe must not publish ports.'
    }
    if ($ContainerMemory) {
        $null = Invoke-Docker "start $name"
        . (Join-Path $PSScriptRoot 'memory-probe.ps1')
        Invoke-ContainerMemoryProbe -ContainerName $name -DockerPath $docker -Fixture $fixture
    }
    elseif ($RuntimeTimeout) {
        $null = Invoke-Docker "start $name"
        . (Join-Path $PSScriptRoot 'timeout-probe.ps1')
        Invoke-RuntimeTimeoutProbe -ContainerName $name -DockerPath $docker -Fixture $fixture -WithChild:$ChildTimeout -Cancel:$Cancel
    }
    else {
        $output = Invoke-Docker "start --attach $name" 15000
        $finished = (Invoke-Docker "inspect $name" | ConvertFrom-Json)[0]
        if ($finished.State.Running -or $finished.State.ExitCode -ne 0 -or $finished.State.OOMKilled) {
            throw 'Fixed probe commands did not finish successfully.'
        }
        if ($Heap) {
            if ($output -cne 'heap-ok') { throw "Unexpected heap output: $output" }
            Write-Output 'PASS: 128 MiB Java heap; caught exhaustion continues and exits 0 with exact output and empty stderr.'
            Write-Output 'PASS: uncaught exhaustion exits 1 with Java heap-space error and no continuation.'
            Write-Output 'PASS: cgroup OOM/kill counters remain zero; container did not report OOMKilled.'
        }
        elseif ($StorageInodes) {
            if ($output -cne 'storage-inodes-ok') { throw "Unexpected inode output: $output" }
            Write-Output 'PASS: /work inode capacity 4096; /tmp and /dev/shm inode capacity 1024 each.'
            Write-Output 'PASS: file/directory creation rejected at zero free inodes with byte capacity remaining.'
            Write-Output 'PASS: probe files/directories removed, baseline inode counts restored, and creation reusable.'
        }
        elseif ($StorageBytes) {
            if ($output -cne 'storage-bytes-ok') { throw "Unexpected storage-byte output: $output" }
            Write-Output 'PASS: /work capacity 64 MiB; /tmp and /dev/shm capacity 16 MiB each.'
            Write-Output 'PASS: real-page fills reach zero available blocks; extra byte rejected with free inodes remaining.'
            Write-Output 'PASS: probe files removed, available space restored, and small writes reusable on all three mounts.'
        }
        elseif ($Mounts) {
            if ($output -cne 'mounts-ok') { throw "Unexpected mount audit output: $output" }
            Write-Output 'PASS: effective mount inventory matches the pinned profile; no extra configured volumes/devices.'
            Write-Output 'PASS: approved storage ownership, /dev write denial, and masked null devices verified.'
        }
        elseif ($Privileges) {
            if ($output -cne 'privileges-ok') { throw "Unexpected privilege output: $output" }
            Write-Output 'PASS: PID 1 and probe UID/GID 10001, no extra group identities, and all five capability sets empty.'
            Write-Output 'PASS: no-new-privileges enabled and seccomp filter mode active with at least one filter.'
        }
        elseif ($Network) {
            if ($output -cne 'network-ok') { throw "Unexpected network output: $output" }
            Write-Output 'PASS: only loopback interfaces/routes; local socket transfer works.'
            Write-Output 'PASS: representative external/private IPv4 and external IPv6 connections report network unreachable.'
        }
        elseif ($Filesystem) {
            if ($output -cne 'filesystem-ok') { throw "Unexpected filesystem output: $output" }
            Write-Output 'PASS: UID/GID 10001, effective read-only root, and protected writes denied.'
            Write-Output 'PASS: /work, /tmp, and /dev/shm are writable tmpfs; small files round-trip and are removed.'
        }
        else {
            $lines = $output -split '\r?\n'
            if ($lines[0] -ne '10001' -or $lines[1] -ne '10001' -or
                $output -notmatch 'openjdk version "21[."]' -or $output -notmatch '(?m)^javac 21(?:\.|\s|$)' -or
                $lines[-1] -ne 'runner-ok') {
                throw "Unexpected identity/toolchain output: $output"
            }
            Write-Output $output
            Write-Output 'PASS: UID/GID 10001, Java 21, javac 21, and normal container exit.'
            Write-Output 'PASS: fixture compiled and ran with exact runner-ok output plus LF and empty stderr.'
        }
    }
}
catch { $failed = $true; Write-Output "FAIL: $($_.Exception.Message)" }
finally {
    # Remove only containers carrying this invocation's unique ownership label.
    $cleanup = [Diagnostics.Stopwatch]::StartNew()
    try {
        $ids = Invoke-Docker "ps --all --quiet --no-trunc --filter label=$label"
        if ($ids) {
            if ($ids -notmatch '^[0-9a-f]{64}$') { throw 'Unexpected ownership inventory; manual review required.' }
            $remaining = 5000 - [int]$cleanup.ElapsedMilliseconds
            if ($remaining -le 0) { throw 'Cleanup deadline expired.' }
            $null = Invoke-Docker "rm --force $ids" $remaining
        }
        $remaining = 5000 - [int]$cleanup.ElapsedMilliseconds
        if ($remaining -le 0) { throw 'Cleanup deadline expired.' }
        $left = Invoke-Docker "ps --all --quiet --filter label=$label" $remaining
        if ($left -or $uncertain) { throw 'Cleanup not proven; a container or timed-out daemon operation may remain.' }
        Write-Output 'PASS: owned smoke container removed and absence verified.'
    }
    catch { $failed = $true; Write-Output "FAIL: cleanup for $name : $($_.Exception.Message)" }
}
if ($failed) { exit 1 }
exit 0
