param(
    [string]$InstallRoot = "E:\tools\ffmpeg",
    [string]$Version = "8.1.2"
)

$ErrorActionPreference = "Stop"
$packageName = "ffmpeg-$Version-essentials_build.zip"
$baseUrl = "https://www.gyan.dev/ffmpeg/builds/packages"
$versionRoot = Join-Path $InstallRoot $Version
$archivePath = Join-Path $versionRoot $packageName
$checksumPath = "$archivePath.sha256"
$extractRoot = Join-Path $versionRoot "expanded"
$binRoot = Join-Path $versionRoot "bin"

New-Item -ItemType Directory -Force -Path $versionRoot, $extractRoot, $binRoot | Out-Null
Invoke-WebRequest -Uri "$baseUrl/$packageName" -OutFile $archivePath
Invoke-WebRequest -Uri "$baseUrl/$packageName.sha256" -OutFile $checksumPath

$expectedHash = ((Get-Content -LiteralPath $checksumPath -Raw).Trim() -split '\s+')[0]
$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash
if ($actualHash -ne $expectedHash) {
    throw "FFmpeg archive SHA256 mismatch. Expected $expectedHash, got $actualHash"
}

Expand-Archive -LiteralPath $archivePath -DestinationPath $extractRoot -Force
$ffmpegSource = Get-ChildItem -LiteralPath $extractRoot -Filter "ffmpeg.exe" -File -Recurse | Select-Object -First 1
$ffprobeSource = Get-ChildItem -LiteralPath $extractRoot -Filter "ffprobe.exe" -File -Recurse | Select-Object -First 1
if (-not $ffmpegSource -or -not $ffprobeSource) { throw "Archive does not contain ffmpeg.exe and ffprobe.exe" }

Copy-Item -LiteralPath $ffmpegSource.FullName -Destination (Join-Path $binRoot "ffmpeg.exe") -Force
Copy-Item -LiteralPath $ffprobeSource.FullName -Destination (Join-Path $binRoot "ffprobe.exe") -Force
& (Join-Path $binRoot "ffmpeg.exe") -version | Select-Object -First 1
& (Join-Path $binRoot "ffprobe.exe") -version | Select-Object -First 1

Write-Host "Installed. Configure media.ffmpeg=$($binRoot.Replace('\','/'))/ffmpeg.exe"
Write-Host "Configure media.ffprobe=$($binRoot.Replace('\','/'))/ffprobe.exe"
