$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaCompiler = 'C:\Program Files\Java\jdk-17\bin\javac.exe'

if (-not (Test-Path -LiteralPath $javaCompiler)) {
    throw "JDK 17 was not found at C:\Program Files\Java\jdk-17"
}

docker info | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Engine is not available. Start Docker Desktop and try again.'
}
& (Join-Path $projectRoot 'mvnw.cmd') clean verify
if ($LASTEXITCODE -ne 0) {
    throw "Maven verification failed with exit code $LASTEXITCODE"
}

Write-Host 'Verification passed. WAR: target\ROOT.war'
