param(
    [string]$InstallRoot = "E:\tools\canal",
    [string]$Version = "1.1.8",
    [string]$MySqlAddress = "127.0.0.1:3306",
    [string]$Database = "teriteri",
    [string]$MySqlUser = "canal",
    [string]$MySqlPassword = "canal"
)

$ErrorActionPreference = "Stop"
$versionRoot = Join-Path $InstallRoot $Version
$archive = Join-Path $versionRoot "canal.deployer-$Version.tar.gz"
New-Item -ItemType Directory -Force -Path $versionRoot | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    $url = "https://github.com/alibaba/canal/releases/download/canal-$Version/canal.deployer-$Version.tar.gz"
    Invoke-WebRequest -Uri $url -OutFile $archive
}

& tar.exe -xzf $archive -C $versionRoot
$instanceRoot = Join-Path $versionRoot "conf\example"
New-Item -ItemType Directory -Force -Path $instanceRoot | Out-Null
$escapedDatabase = [regex]::Escape($Database)
$instanceConfig = @"
canal.instance.mysql.slaveId=1234
canal.instance.master.address=$MySqlAddress
canal.instance.dbUsername=$MySqlUser
canal.instance.dbPassword=$MySqlPassword
canal.instance.connectionCharset=UTF-8
canal.instance.gtidon=false
canal.instance.filter.regex=$escapedDatabase\\.(video|video_stats|user|category)
canal.instance.filter.black.regex=
"@
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $instanceRoot "instance.properties"), $instanceConfig, $utf8NoBom)

$startup = Join-Path $versionRoot "bin\startup.bat"
if (-not (Test-Path -LiteralPath $startup)) { throw "Canal startup.bat was not found after extraction" }
Start-Process -FilePath $startup -WorkingDirectory (Split-Path -Parent $startup) -WindowStyle Hidden
Write-Host "Canal $Version started. Check $versionRoot\logs\canal\canal.log"
