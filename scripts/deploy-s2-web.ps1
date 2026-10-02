param([string]$ConfigPath = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
if ((git branch --show-current) -ne 'main') { throw 'Triển khai phải chạy từ nhánh main đã nghiệm thu.' }
git diff --exit-code -- src pom.xml scripts | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Code triển khai còn thay đổi chưa commit.' }
if (Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue) { throw 'Cổng 8081 đang được dùng.' }
if (!$ConfigPath) { $ConfigPath = Join-Path $root '.tools/deploy-8081.json' }
$config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
foreach ($field in @('mailFrom','mailUsername','mailPassword','testRecipient')) {
    if ([string]::IsNullOrWhiteSpace($config.$field)) { throw "Thiếu cấu hình local: $field" }
}
if ($config.mailHost -ne 'smtp.gmail.com' -or $config.mailPort -ne 587 -or
    !$config.mailAuthEnabled -or !$config.mailStarttlsEnabled -or !$config.mailStarttlsRequired -or
    $config.appBaseUrl -ne 'https://hethong.nvdo.id.vn') { throw 'Cấu hình SMTP/HTTPS không đúng bản đã chốt.' }
$tools = Join-Path $root '.tools'
$evidence = Get-Content -LiteralPath (Join-Path $tools 's2-clean-verify.json') -Raw | ConvertFrom-Json
$tree = git rev-parse 'HEAD^{tree}'
if (!$evidence.passed -or $evidence.tree -ne $tree -or $evidence.skipped -ne 0) { throw 'Thiếu bằng chứng clean verify đạt cho đúng cây code.' }
$sha = git rev-parse HEAD
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
& (Join-Path $root 'mvnw.cmd') -B -ntp package *> (Join-Path $tools 's2-main-package.log')
if ($LASTEXITCODE -ne 0) { throw 'Đóng gói main thất bại; xem log local.' }
if ((git rev-parse HEAD) -ne $sha) { throw 'SHA đã đổi trong lúc đóng gói.' }
$tomcatHome = Join-Path (Split-Path -Parent $root) 'He_thong_ban_hang_va_kho/.tools/apache-tomcat-10.1.60'
$base = Join-Path $tools 's2-web-tomcat'
foreach ($folder in @('conf','logs','temp','webapps','work')) { New-Item -ItemType Directory -Force -Path (Join-Path $base $folder) | Out-Null }
Copy-Item -Path (Join-Path $tomcatHome 'conf/*') -Destination (Join-Path $base 'conf') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'tomcat-s2-web.xml') -Destination (Join-Path $base 'conf/server.xml') -Force
if (Test-Path -LiteralPath (Join-Path $base 'webapps/ROOT')) { throw 'Đã có web giải nén; phải dừng và lưu bản cũ trước khi triển khai lại.' }
Copy-Item -LiteralPath (Join-Path $root 'target/ROOT.war') -Destination (Join-Path $base 'webapps/ROOT.war') -Force
$env:DB_URL = 'jdbc:mysql://127.0.0.1:13311/sales_inventory?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false'
$env:DB_USERNAME = 'sales_app'; $env:DB_PASSWORD = 'sales_app123'
$env:APP_BASE_URL = $config.appBaseUrl
$env:APP_UPLOAD_DIR = Join-Path $tools 's2-web-uploads'
$env:MAIL_HOST = $config.mailHost; $env:MAIL_PORT = [string]$config.mailPort
$env:MAIL_FROM = $config.mailFrom; $env:MAIL_USERNAME = $config.mailUsername
$env:MAIL_PASSWORD = $config.mailPassword -replace '\s',''
$env:MAIL_AUTH_ENABLED = 'true'; $env:MAIL_STARTTLS_ENABLED = 'true'; $env:MAIL_STARTTLS_REQUIRED = 'true'
try {
    $classpath = (Join-Path $tomcatHome 'bin/bootstrap.jar') + ';' + (Join-Path $tomcatHome 'bin/tomcat-juli.jar')
    $arguments = @('-Duser.timezone=Asia/Ho_Chi_Minh',"-Dcatalina.home=$tomcatHome","-Dcatalina.base=$base",
        ("-Djava.io.tmpdir=" + (Join-Path $base 'temp')),'-Dfile.encoding=UTF-8','-cp',$classpath,'org.apache.catalina.startup.Bootstrap','start')
    $process = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $base 'logs/stdout.log') -RedirectStandardError (Join-Path $base 'logs/stderr.log')
    [pscustomobject]@{sha=$sha; tree=$tree; pid=$process.Id; warHash=(Get-FileHash -LiteralPath (Join-Path $base 'webapps/ROOT.war')).Hash; deployedAt=(Get-Date).ToString('o')} |
        ConvertTo-Json | Out-File (Join-Path $tools 's2-deployment.json')
    Write-Output "Tomcat main $sha đã khởi chạy trên 127.0.0.1:8081, PID $($process.Id)."
} finally {
    Remove-Item Env:MAIL_PASSWORD -ErrorAction SilentlyContinue
    $config=$null
}
