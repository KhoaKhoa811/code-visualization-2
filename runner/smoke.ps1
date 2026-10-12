#requires -Version 5.1
# Fixed trusted tool commands only. This is not an executor for submitted Java.
$ErrorActionPreference = 'Stop'
$pin = Get-Content (Join-Path $PSScriptRoot 'image.json') -Raw | ConvertFrom-Json
if ($pin.image -notmatch '^eclipse-temurin@sha256:[0-9a-f]{64}$' -or $pin.platform -ne 'linux/amd64') {
    throw 'Expected the reviewed linux/amd64 image pin.'
}
& (Join-Path $PSScriptRoot 'preflight.ps1') -Image $pin.image
if ($LASTEXITCODE -ne 0) { exit 1 }
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
    $probe = 'id -u; id -g; java -Xmx128m -Djava.io.tmpdir=/tmp -version 2>&1; javac -J-Xmx256m -J-Djava.io.tmpdir=/tmp -version 2>&1'
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
    $output = Invoke-Docker "start --attach $name" 15000
    $finished = (Invoke-Docker "inspect $name" | ConvertFrom-Json)[0]
    if ($finished.State.Running -or $finished.State.ExitCode -ne 0 -or $finished.State.OOMKilled) {
        throw 'Toolchain commands did not finish successfully.'
    }
    $lines = $output -split '\r?\n'
    if ($lines[0] -ne '10001' -or $lines[1] -ne '10001' -or
        $output -notmatch 'openjdk version "21[."]' -or $output -notmatch '(?m)^javac 21(?:\.|\s|$)') {
        throw "Unexpected identity/toolchain output: $output"
    }
    Write-Output $output
    Write-Output 'PASS: UID/GID 10001, Java 21, javac 21, and normal container exit.'
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
