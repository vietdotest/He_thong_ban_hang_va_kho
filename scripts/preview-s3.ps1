param([int]$Port=18093,[int]$DatabasePort=13319,[int]$MailPort=11039,[int]$MailUiPort=18095,
 [string]$TomcatHome='C:\Users\vietdo1201\Desktop\Codegym\CodeGym\He_thong_ban_hang_va_kho\.tools\apache-tomcat-10.1.60')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$javaHomePath='C:\Program Files\Java\jdk-17'
if(!(Test-Path (Join-Path $TomcatHome 'bin/bootstrap.jar'))){throw 'Tomcat 10.1 chưa có.'}
if(!(Test-Path (Join-Path $projectRoot 'target/ROOT.war'))){throw 'Cần build ROOT.war trước.'}
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw 'Cổng QA đang dùng.'}
$databaseContainer='codegym-s3-qa-db'
$mailContainer='codegym-s3-qa-mail'
if(docker ps -a --filter "name=^/$databaseContainer`$" --format '{{.Names}}'){
 docker start $databaseContainer | Out-Null
}else{
 docker run -d --name $databaseContainer -p "127.0.0.1:${DatabasePort}:3306" -e MYSQL_DATABASE=sales_inventory -e MYSQL_USER=sales_app -e MYSQL_PASSWORD=sales_app123 -e MYSQL_ROOT_PASSWORD=root123 mysql:8.4.11 | Out-Null
}
if($LASTEXITCODE -ne 0 -or (docker port $databaseContainer 3306/tcp) -ne "127.0.0.1:$DatabasePort"){throw 'Database QA không đúng cổng.'}
if(docker ps -a --filter "name=^/$mailContainer`$" --format '{{.Names}}'){
 docker start $mailContainer | Out-Null
}else{
 docker run -d --name $mailContainer -p "127.0.0.1:${MailPort}:1025" -p "127.0.0.1:${MailUiPort}:8025" axllent/mailpit:v1.27.8 | Out-Null
}
if($LASTEXITCODE -ne 0 -or (docker port $mailContainer 1025/tcp) -ne "127.0.0.1:$MailPort"){throw 'Mailbox QA không đúng cổng.'}
$ready=$false
for($attempt=0;$attempt -lt 60;$attempt++){
 docker exec -e MYSQL_PWD=sales_app123 $databaseContainer mysql -h 127.0.0.1 -usales_app -N -B sales_inventory -e 'SELECT 1' 2>$null | Out-Null
 if($LASTEXITCODE -eq 0){$ready=$true;break}
 Start-Sleep -Seconds 1
}
if(!$ready){throw 'Database QA chưa sẵn sàng.'}
# Disposable QA only: MySQL binary logging otherwise needs SUPER to create migration triggers.
docker exec -e MYSQL_PWD=root123 $databaseContainer mysql -uroot -e 'SET GLOBAL log_bin_trust_function_creators=1' | Out-Null
if($LASTEXITCODE -ne 0){throw 'Không cấu hình được quyền trigger trên MySQL QA riêng.'}
$tomcatBase=Join-Path $projectRoot '.tools/s3-qa-tomcat'
foreach($folder in @('conf','logs','temp','webapps','work')){New-Item -ItemType Directory -Path (Join-Path $tomcatBase $folder) -Force | Out-Null}
Copy-Item -Path (Join-Path $TomcatHome 'conf/*') -Destination (Join-Path $tomcatBase 'conf') -Force
[xml]$server=Get-Content (Join-Path $tomcatBase 'conf/server.xml') -Raw
$server.Server.SetAttribute('port','-1')
$connector=$server.SelectSingleNode('/Server/Service/Connector');$connector.SetAttribute('port',"$Port");$connector.SetAttribute('address','127.0.0.1')
$server.Save((Join-Path $tomcatBase 'conf/server.xml'))
Copy-Item -LiteralPath (Join-Path $projectRoot 'target/ROOT.war') -Destination (Join-Path $tomcatBase 'webapps/ROOT.war') -Force
$env:DB_URL="jdbc:mysql://127.0.0.1:$DatabasePort/sales_inventory?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false"
$env:DB_USERNAME='sales_app';$env:DB_PASSWORD='sales_app123';$env:APP_BASE_URL="http://127.0.0.1:$Port"
$env:MAIL_HOST='127.0.0.1';$env:MAIL_PORT="$MailPort";$env:MAIL_AUTH_ENABLED='false';$env:MAIL_STARTTLS_ENABLED='false';$env:MAIL_STARTTLS_REQUIRED='false';$env:MAIL_USERNAME='';$env:MAIL_PASSWORD=''
$env:APP_UPLOAD_DIR=Join-Path $projectRoot '.tools/s3-qa-uploads'
$classpath=(Join-Path $TomcatHome 'bin/bootstrap.jar')+';'+(Join-Path $TomcatHome 'bin/tomcat-juli.jar')
$arguments=@("-Dcatalina.home=$TomcatHome","-Dcatalina.base=$tomcatBase","-Djava.io.tmpdir=$(Join-Path $tomcatBase 'temp')",'-Dfile.encoding=UTF-8','-cp',$classpath,'org.apache.catalina.startup.Bootstrap','start')
$quoted=$arguments|ForEach-Object{'"'+$_+'"'}
$process=Start-Process -FilePath (Join-Path $javaHomePath 'bin/java.exe') -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $tomcatBase 'logs/stdout.log') -RedirectStandardError (Join-Path $tomcatBase 'logs/stderr.log')
Write-Output "PID=$($process.Id) URL=http://127.0.0.1:$Port DB=$databaseContainer MAIL=http://127.0.0.1:$MailUiPort"
