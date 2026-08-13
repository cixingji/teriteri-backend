param(
    [Parameter(Mandatory = $true)]
    [string]$ElasticsearchHome,
    [string]$ElasticsearchVersion = "7.17.16"
)

$resolvedHome = (Resolve-Path -LiteralPath $ElasticsearchHome).Path
$pluginTool = Join-Path $resolvedHome "bin\elasticsearch-plugin.bat"
if (-not (Test-Path -LiteralPath $pluginTool -PathType Leaf)) {
    throw "Elasticsearch plugin tool was not found: $pluginTool"
}

$installedPlugins = & $pluginTool list
if ($LASTEXITCODE -ne 0) {
    throw "Could not list Elasticsearch plugins. Stop Elasticsearch and verify the installation directory."
}

if ($installedPlugins -match "analysis-ik") {
    Write-Host "IK analysis plugin is already installed."
    exit 0
}

$pluginUrl = "https://get.infini.cloud/elasticsearch/analysis-ik/$ElasticsearchVersion"
Write-Host "Installing the IK analysis plugin matching Elasticsearch $ElasticsearchVersion..."
& $pluginTool install --batch $pluginUrl
if ($LASTEXITCODE -ne 0) {
    throw "IK plugin installation failed. Confirm that the plugin version exactly matches Elasticsearch."
}

Write-Host "IK plugin installed. Restart Elasticsearch before starting the backend."
