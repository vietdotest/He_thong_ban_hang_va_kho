param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'

$baseUrl = $BaseUrl.TrimEnd('/')
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
    -or $dashboard.BaseResponse.RequestMessage.RequestUri.AbsoluteUri -ne "$baseUrl/dashboard" `
    -or $dashboard.Content -notmatch '<title>Tổng quan công việc</title>'
if ($smokeFailed) {
    throw 'Login smoke test failed.'
}

$routes = @(
    '/admin/users', '/admin/roles', '/admin/assignments?id=1', '/admin/scopes', '/admin/audit',
    '/admin/users/import', '/account/profile', '/catalog/products', '/catalog/categories',
    '/catalog/units', '/catalog/suppliers', '/pricing/lists'
)
foreach ($route in $routes) {
    $page = Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl$route" -WebSession $webSession -TimeoutSec 30
    if ($page.StatusCode -ne 200 -or $page.BaseResponse.RequestMessage.RequestUri.AbsoluteUri -ne "$baseUrl$route") {
        throw "Authenticated page smoke test failed: $route"
    }
    Write-Host "OK $route"
}

Write-Host "Smoke test passed: health, CSRF login, persistent session, dashboard and $($routes.Count) authenticated pages."
