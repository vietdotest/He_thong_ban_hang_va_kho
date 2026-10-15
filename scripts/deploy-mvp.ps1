param(
    [string]$ConfigPath = '',
    [string]$RuntimeRoot = '',
    [ValidateSet('Public','Preview')][string]$Target = 'Public',
    [switch]$PreflightOnly,
    [switch]$InjectHealthFailure,
    [switch]$UiOnly,
    [string]$RollbackWar = ''
)
$ErrorActionPreference = 'Stop'
if ($UiOnly -or $RollbackWar) {
    & (Join-Path $PSScriptRoot 'deploy-ui.ps1') -ConfigPath $ConfigPath -RuntimeRoot $RuntimeRoot -Target $Target -PreflightOnly:$PreflightOnly -InjectHealthFailure:$InjectHealthFailure -RollbackWar $RollbackWar
    return
}
$deployRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $deployRoot
if (!$RuntimeRoot) { $RuntimeRoot=Join-Path (Split-Path -Parent $deployRoot) 'He_thong_ban_hang_va_kho-work' }
$runtimeTools=Join-Path ([IO.Path]::GetFullPath($RuntimeRoot)) '.tools'

function Assert-ChildPath([string]$Path,[string]$Parent) {
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    $resolvedParent = [IO.Path]::GetFullPath($Parent).TrimEnd('\','/')
    if (!$resolvedPath.StartsWith($resolvedParent+'\',[StringComparison]::OrdinalIgnoreCase)) {
        throw "Đường dẫn nằm ngoài thư mục được phép: $resolvedPath"
    }
    return $resolvedPath
}
function Test-TomcatProcess($Process,[string]$Base,[string]$TomcatHome) {
    if (!$Process -or !$Process.CommandLine) { return $false }
    $baseArgument = '(?i)(?:^|\s|")-Dcatalina\.base='+[Regex]::Escape($Base)+'(?=\s|"|$)'
    $homeArgument = '(?i)(?:^|\s|")-Dcatalina\.home='+[Regex]::Escape($TomcatHome)+'(?=\s|"|$)'
    return $Process.ExecutablePath -eq 'C:\Program Files\Java\jdk-17\bin\java.exe' -and
        $Process.CommandLine -match $baseArgument -and $Process.CommandLine -match $homeArgument -and
        $Process.CommandLine.Contains('org.apache.catalina.startup.Bootstrap')
}
function Stop-ExpectedTomcat([int]$ProcessId,[string]$Base,[string]$TomcatHome) {
    $runningProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if (!$runningProcess) {
        if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) { throw 'Chưa xác minh được tiến trình; không dừng.' }
        return
    }
    if (!(Test-TomcatProcess $runningProcess $Base $TomcatHome)) { throw 'Tiến trình đã đổi; không dừng tiến trình ngoài Tomcat đã xác minh.' }
    Stop-Process -Id $ProcessId
    for ($attempt=0;$attempt -lt 20;$attempt++) {
        if (!(Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return }
        Start-Sleep -Milliseconds 250
    }
    throw 'Tomcat chưa dừng; không thay ứng dụng.'
}
function Start-ReleaseTomcat([string]$ReleaseSha) {
    $classpath = (Join-Path $deployHome 'bin/bootstrap.jar')+';'+(Join-Path $deployHome 'bin/tomcat-juli.jar')
    $arguments = @('-Duser.timezone=Asia/Ho_Chi_Minh',"-Dcatalina.home=$deployHome","-Dcatalina.base=$deployBase",
        ("-Djava.io.tmpdir="+(Join-Path $deployBase 'temp')),'-Dfile.encoding=UTF-8')
    if ($ReleaseSha -match '^[a-f0-9]{40}$') { $arguments += "-Dapp.release.sha=$ReleaseSha" }
    $arguments += @('-cp',$classpath,'org.apache.catalina.startup.Bootstrap','start')
    $launch=@{
        FilePath='C:/Program Files/Java/jdk-17/bin/java.exe';ArgumentList=$arguments;WindowStyle='Hidden';PassThru=$true
        RedirectStandardOutput=(Join-Path $deployBase 'logs/stdout.log');RedirectStandardError=(Join-Path $deployBase 'logs/stderr.log')
    }
    return Start-Process @launch
}
function Wait-ReleaseHealth([string]$ExpectedSha) {
    for ($attempt=0;$attempt -lt 45;$attempt++) {
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$deployPort/health" -TimeoutSec 3
            if ($health.status -eq 'UP' -and $health.database -eq 'UP' -and (!$ExpectedSha -or $health.release -eq $ExpectedSha)) { return }
        } catch {}
        Start-Sleep -Milliseconds 1000
    }
    throw 'Health local không đạt.'
}
function Assert-PublicHealth([string]$ExpectedSha) {
    if ($Target -ne 'Public') { return }
    for ($attempt=0;$attempt -lt 4;$attempt++) {
        try {
            $health = Invoke-RestMethod -Uri "$($deployConfig.appBaseUrl)/health" -TimeoutSec 10
            $login = Invoke-WebRequest -Uri "$($deployConfig.appBaseUrl)/login" -TimeoutSec 10
            if ($health.status -eq 'UP' -and $health.database -eq 'UP' -and $login.StatusCode -eq 200 -and
                (!$ExpectedSha -or $health.release -eq $ExpectedSha)) { return }
        } catch {}
        Start-Sleep -Seconds 2
    }
    throw 'Health hoặc đăng nhập public không đạt.'
}

$releaseBranch=git branch --show-current
if ($Target -eq 'Public' -and $releaseBranch -ne 'main') { throw 'Public phải phát hành từ main đã tích hợp và kiểm thử.' }
if ($Target -eq 'Preview' -and $releaseBranch -notmatch '^(main|develop|feature/.+)$') { throw 'Preview cần nhánh chức năng, develop hoặc main.' }
$sourceChanges=@(git status --porcelain --untracked-files=all -- src pom.xml scripts)
if ($sourceChanges.Count -ne 0) { throw 'Code triển khai còn thay đổi hoặc file chưa commit.' }
if ($InjectHealthFailure -and $Target -ne 'Preview') { throw 'Chỉ mô phỏng lỗi trên môi trường kiểm thử.' }
$deploySha = git rev-parse HEAD
$deployTree = git rev-parse 'HEAD^{tree}'
$deployTools = Join-Path $deployRoot '.tools'
$deployWar = Join-Path $deployRoot 'target/ROOT.war'
$evidence = Get-Content -LiteralPath (Join-Path $deployTools 'mvp-clean-verify.json') -Raw | ConvertFrom-Json
if (!$evidence.passed -or $evidence.tree -ne $deployTree -or $evidence.skipped -ne 0 -or
    $evidence.failures -ne 0 -or $evidence.errors -ne 0 -or $evidence.warHash -ne (Get-FileHash -LiteralPath $deployWar).Hash) {
    throw 'Thiếu clean verify đạt và WAR tương ứng đúng cây code.'
}
if ($Target -eq 'Public') {
    $approvalPath=Join-Path $deployTools 'business-ui-approval.json'
    if (!(Test-Path -LiteralPath $approvalPath)) { throw 'Thiếu bằng chứng duyệt giao diện Preview của đợt nghiệp vụ.' }
    $approval=Get-Content -LiteralPath $approvalPath -Raw | ConvertFrom-Json
    if (!$approval.approved -or $approval.tree -ne $deployTree -or !$evidence.schemaRollbackCompatible) { throw 'Chưa duyệt đúng cây code hoặc chưa kiểm rollback giữ dữ liệu trên schema mới.' }
}
$deployHome = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $deployRoot) 'He_thong_ban_hang_va_kho/.tools/apache-tomcat-10.1.60'))
if (!(Test-Path -LiteralPath (Join-Path $deployHome 'bin/bootstrap.jar'))) { throw 'Không tìm thấy Tomcat hiện có.' }
if ($Target -eq 'Public') {
    $deployPort=8081; $deployDbPort=13311; $deployContainer='codegym-s2-web-db'
    $deployBase=Join-Path $runtimeTools 's2-web-tomcat'; $deployUploads=Join-Path $runtimeTools 's2-web-uploads'
    $deploymentFile=Join-Path $runtimeTools 's2-deployment.json'
    if (!$ConfigPath) { $ConfigPath=Join-Path $deployTools 'mvp-deploy-8081.json' }
    $deployConfig=Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    foreach ($field in @('mailFrom','mailUsername','mailPassword','dbUsername','dbPassword','migrationUsername','migrationPassword')) {
        if ([string]::IsNullOrWhiteSpace($deployConfig.$field)) { throw "Thiếu cấu hình local: $field" }
    }
    if ($deployConfig.dbUsername -eq $deployConfig.migrationUsername -or $deployConfig.dbUsername -notmatch '^[A-Za-z0-9_]+$' -or $deployConfig.migrationUsername -notmatch '^[A-Za-z0-9_]+$') { throw 'Tài khoản migration phải riêng và có tên hợp lệ.' }
    if ($deployConfig.mailHost -ne 'smtp.gmail.com' -or $deployConfig.mailPort -ne 587 -or
        !$deployConfig.mailAuthEnabled -or !$deployConfig.mailStarttlsEnabled -or !$deployConfig.mailStarttlsRequired -or
        $deployConfig.appBaseUrl -ne 'https://hethong.nvdo.id.vn') { throw 'Cấu hình SMTP/HTTPS khác bản đã chốt.' }
} else {
    $deployPort=18087; $deployDbPort=13318; $deployContainer='codegym-mvp-release-rehearsal'
    $runtimeTools=Join-Path ([IO.Path]::GetFullPath($RuntimeRoot)) '.tools'
    $deployBase=Join-Path $runtimeTools 'mvp-rehearsal-tomcat'; $deployUploads=Join-Path $runtimeTools 'mvp-rehearsal-uploads'
    $deploymentFile=Join-Path $runtimeTools 'mvp-rehearsal-deployment.json'
    if (!$ConfigPath) { throw 'Preview cần cấu hình local của database phục hồi tạm.' }
    $deployConfig=Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    if ($deployConfig.appBaseUrl -ne 'http://127.0.0.1:18087' -or $deployConfig.mailAuthEnabled -or $deployConfig.mailStarttlsEnabled -or $deployConfig.dbUsername -eq $deployConfig.migrationUsername) { throw 'Preview phải cô lập và dùng migration riêng.' }
}
$deployBase=Assert-ChildPath $deployBase $runtimeTools
$deployUploads=Assert-ChildPath $deployUploads $runtimeTools
$containerInfo=docker inspect $deployContainer | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or !$containerInfo[0].State.Running) { throw 'Database hiện có chưa chạy.' }
if ($Target -eq 'Preview' -and ($containerInfo[0].Config.Labels.'nvdo.mvp.rehearsal' -ne 'temporary' -or !$containerInfo[0].HostConfig.Tmpfs.'/var/lib/mysql')) { throw 'Preview chỉ dùng database tạm đã gắn nhãn và tmpfs.' }
$binding=$containerInfo[0].NetworkSettings.Ports.'3306/tcp'
if (!$binding -or $binding[0].HostIp -ne '127.0.0.1' -or $binding[0].HostPort -ne "$deployDbPort") { throw 'Database không đúng cổng đã chốt.' }
if ($Target -eq 'Public' -and !(@($containerInfo[0].Mounts | Where-Object {$_.Type -eq 'volume' -and $_.Name -eq 'codegym-s2-web-data' -and $_.Destination -eq '/var/lib/mysql'}).Count -eq 1)) { throw 'Volume database public khác đích đã chốt.' }
$listener=Get-NetTCPConnection -State Listen -LocalPort $deployPort -ErrorAction SilentlyContinue
$previousProcess=$null
if ($listener) {
    $owners=@($listener.OwningProcess | Select-Object -Unique)
    if ($owners.Count -ne 1) { throw 'Cổng triển khai có nhiều tiến trình.' }
    $previousProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($owners[0])"
    if (!(Test-TomcatProcess $previousProcess $deployBase $deployHome)) { throw 'Cổng do tiến trình khác sử dụng; không dừng.' }
    if (!(Test-Path -LiteralPath (Join-Path $deployBase 'webapps/ROOT.war'))) { throw 'Thiếu WAR hiện tại để phục hồi; chưa dừng website.' }
    Wait-ReleaseHealth ''
    Assert-PublicHealth ''
} else {
    $pendingProcesses=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {Test-TomcatProcess $_ $deployBase $deployHome})
    if ($pendingProcesses.Count -gt 0) { throw 'Tomcat đang khởi động hoặc gặp lỗi; cần kiểm tra trước khi thay bản.' }
}
Write-Output "Preflight đạt: $releaseBranch $deploySha, WAR đã kiểm thử, database $deployContainer, cổng $deployPort."
if ($PreflightOnly) { return }
$env:DB_URL="jdbc:mysql://127.0.0.1:$deployDbPort/sales_inventory?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false"
$env:DB_USERNAME=$deployConfig.dbUsername;$env:DB_PASSWORD=$deployConfig.dbPassword;$env:APP_BASE_URL=$deployConfig.appBaseUrl;$env:APP_UPLOAD_DIR=$deployUploads
$env:MAIL_HOST=$deployConfig.mailHost;$env:MAIL_PORT=[string]$deployConfig.mailPort;$env:MAIL_FROM=$deployConfig.mailFrom
$env:MAIL_USERNAME=$deployConfig.mailUsername;$env:MAIL_PASSWORD=$deployConfig.mailPassword -replace '\s',''
$env:MAIL_AUTH_ENABLED=[string]$deployConfig.mailAuthEnabled;$env:MAIL_STARTTLS_ENABLED=[string]$deployConfig.mailStarttlsEnabled;$env:MAIL_STARTTLS_REQUIRED=[string]$deployConfig.mailStarttlsRequired
$env:MIGRATION_ENABLED='false';Remove-Item Env:MIGRATION_USERNAME,Env:MIGRATION_PASSWORD -ErrorAction SilentlyContinue
$oldSha='';if(Test-Path -LiteralPath $deploymentFile){$oldSha=(Get-Content -LiteralPath $deploymentFile -Raw|ConvertFrom-Json).sha}
$stoppedForBackup=$false
if($previousProcess){Stop-ExpectedTomcat $previousProcess.ProcessId $deployBase $deployHome;$stoppedForBackup=$true}
try {

$backup=Assert-ChildPath (Join-Path $deployTools ("releases/"+$Target.ToLower()+"/"+(Get-Date -Format 'yyyyMMdd-HHmmss')+"-"+$deploySha.Substring(0,8))) $deployTools
New-Item -ItemType Directory -Path $backup | Out-Null
$dbFile='/tmp/nvdo-'+[Guid]::NewGuid().ToString('N')+'.sql'
docker exec $deployContainer sh -c ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers --events --no-tablespaces sales_inventory > '+$dbFile)
if ($LASTEXITCODE -ne 0) { throw 'Sao lưu database thất bại.' }
docker cp ($deployContainer+':'+$dbFile) (Join-Path $backup 'database.sql')
if ($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath (Join-Path $backup 'database.sql')).Length -lt 100) { throw 'Bản sao lưu database không hợp lệ.' }
docker exec $deployContainer rm -- $dbFile | Out-Null
if (Test-Path -LiteralPath $deployUploads) { Copy-Item -LiteralPath $deployUploads -Destination (Join-Path $backup 'uploads') -Recurse }
if (Test-Path -LiteralPath (Join-Path $deployBase 'conf')) { Copy-Item -LiteralPath (Join-Path $deployBase 'conf') -Destination (Join-Path $backup 'conf') -Recurse }
if ($Target -eq 'Public') { Copy-Item -LiteralPath $ConfigPath -Destination (Join-Path $backup 'deploy-config.json') }
$oldWar=Join-Path $deployBase 'webapps/ROOT.war'
if (Test-Path -LiteralPath $oldWar) { Copy-Item -LiteralPath $oldWar -Destination (Join-Path $backup 'ROOT.war') }
$oldSha=''
if (Test-Path -LiteralPath $deploymentFile) {
    Copy-Item -LiteralPath $deploymentFile -Destination (Join-Path $backup 'deployment.json')
    $oldSha=(Get-Content -LiteralPath $deploymentFile -Raw | ConvertFrom-Json).sha
}
if ($oldSha -match '^[a-f0-9]{40}$' -and (Test-Path -LiteralPath (Join-Path $backup 'ROOT.war'))) {
    $oldTree=git rev-parse ($oldSha+'^{tree}')
    [pscustomobject]@{sha=$oldSha;tree=$oldTree;warHash=(Get-FileHash -LiteralPath (Join-Path $backup 'ROOT.war')).Hash;target=$Target;base=$deployBase;container=$deployContainer;schemaCompatible=$true;allowedMigration='src/main/resources/db/migration/V044__sales_cost_snapshots.sql'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backup 'ui-backup.json') -Encoding utf8
}
Write-Output "Đã sao lưu database, uploads, WAR và cấu hình tại $backup."
& (Join-Path $PSScriptRoot 'verify-mvp-backup.ps1') -BackupDirectory $backup -Upgrade
}catch{if($stoppedForBackup){$restored=Start-ReleaseTomcat $oldSha;Wait-ReleaseHealth $oldSha;Assert-PublicHealth $oldSha};throw}
$newProcess=$null; $applicationMoved=$false;$migrationAttempted=$false
try {
    if ($previousProcess -and !$stoppedForBackup) { Stop-ExpectedTomcat $previousProcess.ProcessId $deployBase $deployHome }
    $migrationAttempted=$true
    try{$env:MIGRATION_USERNAME=$deployConfig.migrationUsername;$env:MIGRATION_PASSWORD=$deployConfig.migrationPassword;& (Join-Path $PSScriptRoot 'migrate-database.ps1') -Run -JavaHomePath 'C:\Program Files\Java\jdk-17'}finally{$env:MIGRATION_ENABLED='false';Remove-Item Env:MIGRATION_USERNAME,Env:MIGRATION_PASSWORD -ErrorAction SilentlyContinue}
    foreach ($folder in @('conf','logs','temp','webapps','work')) { New-Item -ItemType Directory -Force -Path (Join-Path $deployBase $folder) | Out-Null }
    if (!(Test-Path -LiteralPath (Join-Path $deployBase 'conf/catalina.properties'))) { Copy-Item -Path (Join-Path $deployHome 'conf/*') -Destination (Join-Path $deployBase 'conf') -Force }
    $expanded=Assert-ChildPath (Join-Path $deployBase 'webapps/ROOT') $deployBase
    $compiled=Assert-ChildPath (Join-Path $deployBase 'work/Catalina/localhost/ROOT') $deployBase
    if (Test-Path -LiteralPath $expanded) { Move-Item -LiteralPath $expanded -Destination (Join-Path $backup 'ROOT') }
    $applicationMoved=$true
    if (Test-Path -LiteralPath $compiled) { Move-Item -LiteralPath $compiled -Destination (Join-Path $backup 'work-ROOT') }
    $server=(Get-Content -LiteralPath (Join-Path $PSScriptRoot 'tomcat-s2-web.xml') -Raw).Replace('port="8081"',('port="'+$deployPort+'"'))
    [IO.File]::WriteAllText((Join-Path $deployBase 'conf/server.xml'),$server,[Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath $deployWar -Destination $oldWar -Force
    $newProcess=Start-ReleaseTomcat $deploySha
    Wait-ReleaseHealth $deploySha
    if ($InjectHealthFailure) { throw 'Mô phỏng health thất bại để kiểm tra phục hồi trên Preview.' }
    Assert-PublicHealth $deploySha
    if ((git rev-parse HEAD) -ne $deploySha) { throw 'SHA đã thay đổi trong lúc triển khai.' }
    [pscustomobject]@{sha=$deploySha;tree=$deployTree;pid=$newProcess.Id;warHash=(Get-FileHash -LiteralPath $oldWar).Hash;deployedAt=(Get-Date).ToString('o');backup=$backup;target=$Target;health='UP'} |
        ConvertTo-Json | Set-Content -LiteralPath $deploymentFile -Encoding utf8
    Write-Output "Triển khai $Target thành công: $deploySha, health UP, PID $($newProcess.Id)."
} catch {
    $failure=$_
    if ($newProcess) { Stop-ExpectedTomcat $newProcess.Id $deployBase $deployHome }
    # Migration của đợt này chỉ bổ sung cột nullable. Tuyệt đối không lùi DB/uploads.
    # Nếu schema validate của WAR cũ không đạt, giữ dừng và báo lỗi để xử lý tiến.
    if (!$applicationMoved) {
        if ($previousProcess -and !(Get-Process -Id $previousProcess.ProcessId -ErrorAction SilentlyContinue)) {
            $restored=Start-ReleaseTomcat $oldSha
            Wait-ReleaseHealth ''
            Assert-PublicHealth ''
            Write-Output 'Đã khởi động lại bản hiện tại sau lỗi trước khi thay ứng dụng.'
        }
        throw
    }
    Write-Warning 'Bản mới không đạt; giữ nguyên database/uploads, đang phục hồi WAR cũ.'
    foreach ($relative in @('webapps/ROOT','work/Catalina/localhost/ROOT')) {
        $newPath=Assert-ChildPath (Join-Path $deployBase $relative) $deployBase
        if (Test-Path -LiteralPath $newPath) { Move-Item -LiteralPath $newPath -Destination (Join-Path $backup ($relative.Replace('/','-')+'-failed')) }
    }
    if (Test-Path -LiteralPath (Join-Path $backup 'conf')) { Copy-Item -Path (Join-Path $backup 'conf/*') -Destination (Join-Path $deployBase 'conf') -Recurse -Force }

    if (Test-Path -LiteralPath (Join-Path $backup 'ROOT.war')) { Copy-Item -LiteralPath (Join-Path $backup 'ROOT.war') -Destination $oldWar -Force }
    if (Test-Path -LiteralPath (Join-Path $backup 'ROOT')) { Move-Item -LiteralPath (Join-Path $backup 'ROOT') -Destination (Join-Path $deployBase 'webapps/ROOT') }
    $restored=Start-ReleaseTomcat $oldSha
    Wait-ReleaseHealth ''
    Assert-PublicHealth ''
    $restoredTree=git rev-parse ($oldSha+'^{tree}')
    [pscustomobject]@{sha=$oldSha;tree=$restoredTree;warHash=(Get-FileHash -LiteralPath $oldWar).Hash;pid=$restored.Id;backup=$backup;target=$Target;restored=$true;databasePreserved=$true;uploadsPreserved=$true;deployedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $deploymentFile -Encoding utf8
    [pscustomobject]@{restored=$true;pid=$restored.Id;backup=$backup;at=(Get-Date).ToString('o');reason=$failure.Exception.Message} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backup 'rollback.json') -Encoding utf8
    Write-Output "Đã phục hồi bản cũ, health UP, PID $($restored.Id)."
    throw $failure
} finally {
    Remove-Item Env:MAIL_PASSWORD,Env:DB_PASSWORD,Env:MIGRATION_USERNAME,Env:MIGRATION_PASSWORD -ErrorAction SilentlyContinue
    $deployConfig=$null
}
