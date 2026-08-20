param(
    [Parameter(Mandatory = $true)][string]$KafkaHome,
    [string]$BootstrapServer = "127.0.0.1:9092"
)

$ErrorActionPreference = "Stop"
$topicsScript = Join-Path $KafkaHome "bin\windows\kafka-topics.bat"
if (-not (Test-Path -LiteralPath $topicsScript)) { throw "kafka-topics.bat not found under KafkaHome" }

$topics = @(
    @{ Name = "teriteri.video.play.v1"; Partitions = 12 },
    @{ Name = "teriteri.video.play.v1.DLT"; Partitions = 12 },
    @{ Name = "teriteri.business.log.v1"; Partitions = 6 },
    @{ Name = "teriteri.business.log.v1.DLT"; Partitions = 6 }
)

foreach ($topic in $topics) {
    & $topicsScript --bootstrap-server $BootstrapServer --create --if-not-exists `
        --topic $topic.Name --partitions $topic.Partitions --replication-factor 1
    if ($LASTEXITCODE -ne 0) { throw "Failed to create Kafka topic $($topic.Name)" }
}

Write-Host "Kafka traffic topics are ready on $BootstrapServer."
