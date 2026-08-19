param(
    [Parameter(Mandatory = $true)][string]$KafkaHome,
    [string]$BootstrapServer = "127.0.0.1:9092"
)

$ErrorActionPreference = "Stop"
$topicsScript = Join-Path $KafkaHome "bin\windows\kafka-topics.bat"
if (-not (Test-Path -LiteralPath $topicsScript)) { throw "kafka-topics.bat not found under KafkaHome" }
& $topicsScript --bootstrap-server $BootstrapServer --create --if-not-exists --topic video-index-sync --partitions 6 --replication-factor 1
& $topicsScript --bootstrap-server $BootstrapServer --create --if-not-exists --topic video-index-sync-dlt --partitions 6 --replication-factor 1
Write-Host "Canal synchronization topics are ready."
