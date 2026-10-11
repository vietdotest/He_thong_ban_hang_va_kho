param(
    [int]$Port = 18082,
    [int]$DatabasePort = 13308,
    [string]$TomcatHome = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaHomePath = 'C:\Program Files\Java\jdk-17'
if (-not $TomcatHome) {
    $TomcatHome = Join-Path (Split-Path -Parent $projectRoot) 'He_thong_ban_hang_va_kho\.tools\apache-tomcat-10.1.60'
}
if (-not (Test-Path -LiteralPath (Join-Path $TomcatHome 'bin\bootstrap.jar'))) { throw 'Tomcat 10.1 was not found. Pass -TomcatHome.' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'target\ROOT.war'))) { throw 'Build target/ROOT.war before starting the preview.' }
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Port $Port is in use. Pass a free -Port." }

$containerName = 'codegym-s202-preview'
$existing = docker ps -a --filter "name=^/$containerName`$" --format '{{.Names}}'
if ($existing) {
    docker start $containerName | Out-Null
} else {
    if (Get-NetTCPConnection -State Listen -LocalPort $DatabasePort -ErrorAction SilentlyContinue) { throw "Database port $DatabasePort is in use." }
    docker run -d --name $containerName -p "127.0.0.1:${DatabasePort}:3306" -e MYSQL_DATABASE=sales_inventory -e MYSQL_USER=sales_app -e MYSQL_PASSWORD=sales_app123 -e MYSQL_ROOT_PASSWORD=root123 mysql:8.4.11 | Out-Null
}
if ($LASTEXITCODE -ne 0) { throw 'Cannot start the separate preview database.' }
$mappedPort = docker port $containerName 3306/tcp
if ($mappedPort -ne "127.0.0.1:$DatabasePort") { throw 'Existing preview container uses a different database port.' }
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    docker exec -e MYSQL_PWD=sales_app123 $containerName mysql -h 127.0.0.1 -usales_app -N -B sales_inventory -e 'SELECT 1' 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Preview database did not become ready.' }

$tomcatBase = Join-Path $projectRoot '.tools\profile-preview-tomcat'
foreach ($folder in @('conf','logs','temp','webapps','work')) {
    New-Item -ItemType Directory -Path (Join-Path $tomcatBase $folder) -Force | Out-Null
}
Copy-Item -Path (Join-Path $TomcatHome 'conf\*') -Destination (Join-Path $tomcatBase 'conf') -Force
$serverPath = Join-Path $tomcatBase 'conf\server.xml'
[xml]$server = Get-Content -LiteralPath $serverPath -Raw
$server.Server.SetAttribute('port','-1')
$connector = $server.SelectSingleNode('/Server/Service/Connector')
$connector.SetAttribute('port', "$Port")
$connector.SetAttribute('address', '127.0.0.1')
$server.Save($serverPath)
Copy-Item -LiteralPath (Join-Path $projectRoot 'target\ROOT.war') -Destination (Join-Path $tomcatBase 'webapps\ROOT.war') -Force

$env:DB_URL = "jdbc:mysql://localhost:$DatabasePort/sales_inventory?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
$env:DB_USERNAME = 'sales_app'
$env:DB_PASSWORD = 'sales_app123'
$env:APP_BASE_URL = "http://localhost:$Port"
$env:APP_UPLOAD_DIR = Join-Path $projectRoot '.tools\profile-preview-uploads'
$classpath = (Join-Path $TomcatHome 'bin\bootstrap.jar') + ';' + (Join-Path $TomcatHome 'bin\tomcat-juli.jar')
$arguments = @("-Dcatalina.home=$TomcatHome", "-Dcatalina.base=$tomcatBase", "-Djava.io.tmpdir=$(Join-Path $tomcatBase 'temp')", '-Dfile.encoding=UTF-8', '-cp', $classpath, 'org.apache.catalina.startup.Bootstrap', 'start')
$quotedArguments = $arguments | ForEach-Object { '"' + $_ + '"' }
$process = Start-Process -FilePath (Join-Path $javaHomePath 'bin\java.exe') -ArgumentList $quotedArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $tomcatBase 'logs\stdout.log') -RedirectStandardError (Join-Path $tomcatBase 'logs\stderr.log')
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try {
        $health = Invoke-WebRequest "http://127.0.0.1:$Port/health" -UseBasicParsing -TimeoutSec 2
        if ($health.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
    if ($process.HasExited) { throw 'Preview Tomcat exited. See .tools/profile-preview-tomcat/logs.' }
    Start-Sleep -Seconds 1
}
if (-not $ready) { throw 'Preview Tomcat did not become ready.' }
Write-Output "PID=$($process.Id) URL=http://localhost:$Port/account/profile DATABASE=$containerName"
