#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidatePattern('^$|^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$')]
    [string]$Image = ''
)

$ErrorActionPreference = 'Stop'
$failed = $false

function Report([bool]$Passed, [string]$Message) {
    if ($Passed) { Write-Output "PASS: $Message" }
    else { $script:failed = $true; Write-Output "FAIL: $Message" }
}

# Read-only CLI calls. Keep a stopped daemon from hanging the preflight.
function Read-Tool([string]$Name, [string]$Arguments, [switch]$Unicode) {
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) { throw "$Name is missing from PATH." }
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo.FileName = $command.Source
    $process.StartInfo.Arguments = $Arguments
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    if ($Unicode) {
        $process.StartInfo.StandardOutputEncoding = [Text.Encoding]::Unicode
        $process.StartInfo.StandardErrorEncoding = [Text.Encoding]::Unicode
    }
    $started = $false
    try {
        $started = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(10000)) { throw "$Name check exceeded 10 seconds." }
        if (-not [Threading.Tasks.Task]::WaitAll(@($stdout, $stderr), 1000)) {
            throw "$Name output did not close."
        }
        if ($process.ExitCode -ne 0) {
            throw "$Name check failed (exit $($process.ExitCode)). Check installation, service state, and access."
        }
        return $stdout.Result.Trim()
    }
    finally {
        try { if ($started -and -not $process.HasExited) { $process.Kill() } }
        finally { $process.Dispose() }
    }
}

Write-Output 'Runner preflight: read-only prerequisites, not isolation verification.'
try {
    $distros = Read-Tool wsl.exe '--list --verbose' -Unicode
    # Match the stable distro name and version, not the localized state/header.
    Report ($distros -match '(?m)^\s*\*?\s*docker-desktop\s+.+\s+2\s*$') `
        'WSL lists docker-desktop as version 2. If absent, check Docker Desktop WSL2 setup.'
}
catch { Report $false "WSL: $($_.Exception.Message)" }

$dockerReady = $false
try {
    # Explicit context ignores DOCKER_HOST and avoids inspecting a remote daemon.
    $contexts = Read-Tool docker.exe 'context inspect desktop-linux' | ConvertFrom-Json
    if (@($contexts).Count -ne 1 -or $contexts[0].Endpoints.docker.Host -notmatch '^npipe:/{4}\./pipe/[a-zA-Z0-9._-]+$') {
        throw 'desktop-linux must use a local Windows named pipe.'
    }
    $info = Read-Tool docker.exe '--context desktop-linux info --format "{{json .}}"' | ConvertFrom-Json
    $dockerReady = $info.OSType -eq 'linux' -and $info.OperatingSystem -match 'Docker Desktop'
    Report $dockerReady "Docker Desktop Linux daemon responds (server $($info.ServerVersion))."
}
catch { Report $false "Docker: $($_.Exception.Message) Start Docker Desktop with Linux containers enabled." }

if (-not $Image) {
    Report $false 'No image selected. Rerun with -Image <existing-local-image>; nothing is downloaded.'
}
elseif (-not $dockerReady) {
    Report $false 'Image inspection skipped because Docker readiness failed.'
}
else {
    try {
        $images = Read-Tool docker.exe "--context desktop-linux image inspect $Image" | ConvertFrom-Json
        if (@($images).Count -ne 1) { throw 'Expected exactly one local image.' }
        $candidate = $images[0]
        $java21 = @($candidate.Config.Env | Where-Object { $_ -match '^JAVA_VERSION=(?:jdk-)?21(?:[.+_-]|$)' }).Count -eq 1
        Report ($candidate.Os -eq 'linux' -and $java21) `
            "Image $Image is Linux and declares Java 21 metadata. Missing metadata means unverified."
        Write-Output "Image ID: $($candidate.Id)"
        Write-Output "Repository digests: $(@($candidate.RepoDigests) -join ', ')"
        Write-Output 'Preflight does not verify Java/javac execution, image trust, or isolation.'
    }
    catch { Report $false "Image: $($_.Exception.Message) Verify the image already exists locally." }
}

if ($failed) { exit 1 }
exit 0
