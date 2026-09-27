$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

docker info | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Engine is not available. Start Docker Desktop and try again.'
}
docker compose --project-directory $projectRoot -f (Join-Path $projectRoot 'compose.yaml') up -d --wait mysql mailpit
if ($LASTEXITCODE -ne 0) {
    throw "MySQL container failed to start (docker exit code $LASTEXITCODE)."
}
Write-Host 'MySQL is healthy at localhost:3306. Mailpit is available at http://localhost:8025.'
