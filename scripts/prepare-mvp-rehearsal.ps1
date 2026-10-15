param([Parameter(Mandatory=$true)][string]$BackupDirectory)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$localTools=Join-Path $projectRoot '.tools'
$backupRoot=[IO.Path]::GetFullPath($BackupDirectory)
$databaseFile=Join-Path $backupRoot 'database.sql'
foreach($required in @('database.sql','ROOT.war','conf','deployment.json')){if(!(Test-Path -LiteralPath (Join-Path $backupRoot $required))){throw "Backup thiếu $required."}}
if(Get-NetTCPConnection -State Listen -LocalPort 13318,18087 -ErrorAction SilentlyContinue){throw 'Cổng diễn tập đã có tiến trình; không thay đích đang chạy.'}
$container='codegym-mvp-release-rehearsal'
function Probe-Docker([string[]]$Arguments){$ErrorActionPreference='Continue';$captured=& docker.exe @Arguments 2>&1;return [pscustomobject]@{ExitCode=$LASTEXITCODE;Output=($captured|Out-String)}}
$existing=Probe-Docker @('inspect',$container)
if($existing.ExitCode -eq 0){throw 'Container diễn tập đã tồn tại; giữ nguyên và kiểm tra trước khi chuẩn bị lại.'}
$privatePassword=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N')
$containerId=(docker run -d --name $container --label nvdo.mvp.rehearsal=temporary --tmpfs '/var/lib/mysql:rw,size=1g' -p '127.0.0.1:13318:3306' -e ("MYSQL_ROOT_PASSWORD="+$privatePassword) -e MYSQL_DATABASE=sales_inventory mysql:8.4.11 --log-bin-trust-function-creators=1 --innodb-flush-log-at-trx-commit=2 --sync-binlog=0).Trim()
if($LASTEXITCODE -ne 0 -or $containerId -notmatch '^[a-f0-9]{64}$'){throw 'Không tạo được database diễn tập.'}
$ready=$false
for($attempt=0;$attempt -lt 90;$attempt++){$probe=Probe-Docker @('exec',$containerId,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 --protocol=TCP -uroot -e SELECT/**/1');if($probe.ExitCode -eq 0){$ready=$true;break};Start-Sleep -Milliseconds 1000}
if(!$ready){throw 'Database diễn tập chưa sẵn sàng; giữ container để chẩn đoán.'}
docker cp $databaseFile ($containerId+':/tmp/original.sql') | Out-Null
if($LASTEXITCODE -ne 0){throw 'Không chuyển được backup vào đích diễn tập.'}
docker exec $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 --protocol=TCP -uroot sales_inventory < /tmp/original.sql'
if($LASTEXITCODE -ne 0){throw 'Không phục hồi được bản gốc để diễn tập.'}
("CREATE USER 'mvp_preview_runtime'@'%' IDENTIFIED BY '"+$privatePassword+"'; GRANT SELECT,INSERT,UPDATE,DELETE ON sales_inventory.* TO 'mvp_preview_runtime'@'%'; CREATE USER 'mvp_preview_migration'@'%' IDENTIFIED BY '"+$privatePassword+"'; GRANT ALL ON sales_inventory.* TO 'mvp_preview_migration'@'%';") | docker exec -i $containerId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 --protocol=TCP -uroot sales_inventory'
if($LASTEXITCODE -ne 0){throw 'Không tạo được tài khoản riêng cho diễn tập.'}
$runtimeBase=Join-Path $localTools 'mvp-rehearsal-tomcat'
foreach($folder in @('conf','logs','temp','webapps','work')){New-Item -ItemType Directory -Force -Path (Join-Path $runtimeBase $folder)|Out-Null}
Copy-Item -Path (Join-Path $backupRoot 'conf/*') -Destination (Join-Path $runtimeBase 'conf') -Recurse -Force
$server=(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'tomcat-s2-web.xml') -Raw).Replace('port="8081"','port="18087"')
[IO.File]::WriteAllText((Join-Path $runtimeBase 'conf/server.xml'),$server,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $backupRoot 'ROOT.war') -Destination (Join-Path $runtimeBase 'webapps/ROOT.war')
$uploads=Join-Path $localTools 'mvp-rehearsal-uploads'
if(Test-Path -LiteralPath (Join-Path $backupRoot 'uploads')){Copy-Item -LiteralPath (Join-Path $backupRoot 'uploads') -Destination $uploads -Recurse}else{New-Item -ItemType Directory -Path $uploads | Out-Null}
$config=[pscustomobject]@{appBaseUrl='http://127.0.0.1:18087';dbUsername='mvp_preview_runtime';dbPassword=$privatePassword;migrationUsername='mvp_preview_migration';migrationPassword=$privatePassword;mailHost='127.0.0.1';mailPort=11027;mailFrom='no-reply@localhost';mailUsername='';mailPassword='';mailAuthEnabled=$false;mailStarttlsEnabled=$false;mailStarttlsRequired=$false}
$config | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $localTools 'mvp-preview-config.json') -Encoding utf8
$oldDeployment=Get-Content -LiteralPath (Join-Path $backupRoot 'deployment.json') -Raw | ConvertFrom-Json
$tomcatHome=[IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $projectRoot) 'He_thong_ban_hang_va_kho/.tools/apache-tomcat-10.1.60'))
$env:DB_URL='jdbc:mysql://127.0.0.1:13318/sales_inventory?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false';$env:DB_USERNAME=$config.dbUsername;$env:DB_PASSWORD=$config.dbPassword;$env:MIGRATION_ENABLED='false';Remove-Item Env:MIGRATION_USERNAME,Env:MIGRATION_PASSWORD -ErrorAction SilentlyContinue
$env:APP_BASE_URL=$config.appBaseUrl;$env:APP_UPLOAD_DIR=$uploads;$env:MAIL_HOST=$config.mailHost;$env:MAIL_PORT=[string]$config.mailPort;$env:MAIL_FROM=$config.mailFrom;$env:MAIL_USERNAME='';$env:MAIL_PASSWORD='';$env:MAIL_AUTH_ENABLED='false';$env:MAIL_STARTTLS_ENABLED='false';$env:MAIL_STARTTLS_REQUIRED='false'
$classpath=(Join-Path $tomcatHome 'bin/bootstrap.jar')+';'+(Join-Path $tomcatHome 'bin/tomcat-juli.jar')
$arguments=@('-Duser.timezone=Asia/Ho_Chi_Minh',"-Dcatalina.home=$tomcatHome","-Dcatalina.base=$runtimeBase",("-Djava.io.tmpdir="+(Join-Path $runtimeBase 'temp')),'-Dfile.encoding=UTF-8',("-Dapp.release.sha="+$oldDeployment.sha),'-cp',$classpath,'org.apache.catalina.startup.Bootstrap','start')
$process=Start-Process -FilePath 'C:\Program Files\Java\jdk-17\bin\java.exe' -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeBase 'logs/stdout.log') -RedirectStandardError (Join-Path $runtimeBase 'logs/stderr.log')
$healthReady=$false
for($attempt=0;$attempt -lt 60;$attempt++){try{$health=Invoke-RestMethod http://127.0.0.1:18087/health -TimeoutSec 3;if($health.status -eq 'UP' -and $health.database -eq 'UP' -and $health.release -eq $oldDeployment.sha){$healthReady=$true;break}}catch{};Start-Sleep -Milliseconds 1000}
if(!$healthReady){throw 'Bản gốc không đạt health trên bản phục hồi tạm; chưa diễn tập triển khai.'}
[pscustomobject]@{sha=$oldDeployment.sha;pid=$process.Id;preparedAt=(Get-Date).ToString('o');containerId=$containerId;target='Preview'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $localTools 'mvp-rehearsal-deployment.json') -Encoding utf8
Remove-Item Env:DB_PASSWORD,Env:MAIL_PASSWORD -ErrorAction SilentlyContinue
Write-Output 'Đã chuẩn bị bản gốc trên database tạm và Tomcat 18087, không sửa public.'
