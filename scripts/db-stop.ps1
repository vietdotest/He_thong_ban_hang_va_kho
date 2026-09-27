$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

docker compose --project-directory $projectRoot -f (Join-Path $projectRoot 'compose.yaml') stop mysql mailpit
if ($LASTEXITCODE -ne 0) {
    throw "MySQL container failed to stop (docker exit code $LASTEXITCODE)."
}
Write-Host 'MySQL and Mailpit stopped. Their named volumes were preserved.'
