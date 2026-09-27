$ErrorActionPreference = 'Stop'

$baseUrl = 'http://localhost:8080'
$webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

$health = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/health" -TimeoutSec 10
if ($health.StatusCode -ne 200 -or $health.Content -notmatch '"status":"UP"') {
    throw 'Health check failed.'
}

$loginPage = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/login" -WebSession $webSession -TimeoutSec 10
$csrfMatch = [regex]::Match($loginPage.Content, 'name="_csrf" value="([^"]+)"')
if (-not $csrfMatch.Success) {
    throw 'Login page did not contain a CSRF token.'
}

$dashboard = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/login" -Method Post -WebSession $webSession -Body @{
    identity = 'admin'
    password = 'admin123'
    _csrf = $csrfMatch.Groups[1].Value
} -TimeoutSec 10

$smokeFailed = $dashboard.StatusCode -ne 200 `
    -or $dashboard.BaseResponse.RequestMessage.RequestUri.AbsolutePath -ne '/dashboard' `
    -or $dashboard.Content -notmatch 'Tình hình hôm nay'
if ($smokeFailed) {
    throw 'Login smoke test failed.'
}

Write-Host 'Smoke test passed: health, CSRF login, persistent session and dashboard are working.'
