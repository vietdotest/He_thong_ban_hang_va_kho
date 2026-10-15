param(
    [string]$ConfigPath = '', [string]$RuntimeRoot = '',
    [ValidateSet('Public','Preview')][string]$Target = 'Public',
    [switch]$PreflightOnly, [switch]$InjectHealthFailure, [string]$RollbackWar = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
$java = 'C:\Program Files\Java\jdk-17\bin\java.exe'
$tools = Join-Path $root '.tools'
$tomcatHome = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $root) 'He_thong_ban_hang_va_kho/.tools/apache-tomcat-10.1.60'))
function Child-Path([string]$Path,[string]$Parent) {
    $full = [IO.Path]::GetFullPath($Path)
    $parentFull = [IO.Path]::GetFullPath($Parent).TrimEnd('\','/')
    if (!$full.StartsWith($parentFull+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Đường dẫn nằm ngoài runtime được phép.' }
    return $full
}
function Checked-Git([string[]]$Arguments) {
    $output = & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Không xác minh được lịch sử Git; dừng triển khai.' }
    return ($output -join "`n").Trim()
}
if ($InjectHealthFailure -and $Target -ne 'Preview') { throw 'Chỉ mô phỏng lỗi trên Preview.' }
if (!$RuntimeRoot) { $RuntimeRoot=Join-Path (Split-Path -Parent $root) 'He_thong_ban_hang_va_kho-work' }
$runtimeTools = Join-Path ([IO.Path]::GetFullPath($RuntimeRoot)) '.tools'
if ($Target -eq 'Public') {
    $port=8081; $dbPort=13311; $container='codegym-s2-web-db'
    $base=Child-Path (Join-Path $runtimeTools 's2-web-tomcat') $runtimeTools
    $uploads=Child-Path (Join-Path $runtimeTools 's2-web-uploads') $runtimeTools
    $deployment=Join-Path $runtimeTools 's2-deployment.json'
    if (!$ConfigPath) { $ConfigPath=Join-Path $tools 'mvp-deploy-8081.json' }
} else {
    $port=18087; $dbPort=13318; $container='codegym-mvp-release-rehearsal'
    $base=Child-Path (Join-Path $tools 'mvp-rehearsal-tomcat') $tools
    $uploads=Child-Path (Join-Path $tools 'mvp-rehearsal-uploads') $tools
    $deployment=Join-Path $tools 'mvp-rehearsal-deployment.json'
    if (!$ConfigPath) { $ConfigPath=Join-Path $tools 'mvp-preview-config.json' }
}
$config=Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
if ($config.dbUsername -notmatch '^[A-Za-z0-9_]+$' -or !$config.dbPassword) { throw 'Thiếu tài khoản runtime hợp lệ.' }
if ($Target -eq 'Public') {
    if ($config.appBaseUrl -ne 'https://hethong.nvdo.id.vn' -or $config.mailHost -ne 'smtp.gmail.com' -or $config.mailPort -ne 587 -or !$config.mailAuthEnabled -or !$config.mailStarttlsEnabled -or !$config.mailStarttlsRequired) { throw 'Cấu hình public khác bản đã chốt.' }
} elseif ($config.appBaseUrl -ne 'http://127.0.0.1:18087' -or $config.mailAuthEnabled -or $config.mailStarttlsEnabled) { throw 'Preview phải cô lập.' }
$dbInfo = docker inspect $container | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or !$dbInfo[0].State.Running) { throw 'Database đích chưa chạy.' }
$binding=$dbInfo[0].NetworkSettings.Ports.'3306/tcp'
if (!$binding -or $binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or $binding[0].HostPort -ne "$dbPort") { throw 'Database không đúng đích loopback.' }
if ($Target -eq 'Public' -and @($dbInfo[0].Mounts | Where-Object {$_.Type -eq 'volume' -and $_.Name -eq 'codegym-s2-web-data' -and $_.Destination -eq '/var/lib/mysql'}).Count -ne 1) { throw 'Volume public không đúng.' }
if ($Target -eq 'Preview' -and ($dbInfo[0].Config.Labels.'nvdo.mvp.rehearsal' -ne 'temporary' -or !$dbInfo[0].HostConfig.Tmpfs.'/var/lib/mysql')) { throw 'Database Preview không phải đích tạm đã xác minh.' }
if (!(Test-Path -LiteralPath $deployment)) { throw 'Thiếu metadata bản đang chạy; không có điểm quay lui.' }
$current=Get-Content -LiteralPath $deployment -Raw | ConvertFrom-Json
$oldSha=$current.sha
if ($oldSha -notmatch '^[a-f0-9]{40}$') { throw 'SHA bản đang chạy không hợp lệ.' }
$war=Child-Path (Join-Path $base 'webapps/ROOT.war') $base
if (!(Test-Path -LiteralPath $war)) { throw 'Thiếu WAR hiện tại.' }
$releases=Join-Path $tools ('releases/'+$Target.ToLower())
if ($RollbackWar) {
    $backup=Child-Path $RollbackWar $releases
    $metadata=Get-Content -LiteralPath (Join-Path $backup 'ui-backup.json') -Raw | ConvertFrom-Json
    if ($metadata.target -ne $Target -or $metadata.base -ne $base -or $metadata.container -ne $container -or $metadata.sha -notmatch '^[a-f0-9]{40}$') { throw 'Backup thuộc runtime khác.' }
    $candidate=Join-Path $backup 'ROOT.war'; $sha=$metadata.sha
    if ((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash -ne $metadata.warHash) { throw 'WAR backup không đúng hash.' }
} else {
    $branch=Checked-Git @('branch','--show-current')
    if (($Target -eq 'Public' -and $branch -ne 'main') -or ($Target -eq 'Preview' -and $branch -notin @('ui/tailwind','develop','main'))) { throw 'Preview dùng nhánh UI/tích hợp; Public chỉ dùng main đã nghiệm thu.' }
    if (Checked-Git @('status','--porcelain','--untracked-files=all','--','src','pom.xml','scripts')) { throw 'Mã triển khai chưa commit.' }
    $sha=Checked-Git @('rev-parse','HEAD'); $tree=Checked-Git @('rev-parse','HEAD^{tree}')
    $candidate=Join-Path $root 'target/ROOT.war'
    $evidence=Get-Content -LiteralPath (Join-Path $tools 'mvp-clean-verify.json') -Raw | ConvertFrom-Json
    if (!$evidence.passed -or $evidence.tree -ne $tree -or $evidence.skipped -ne 0 -or $evidence.failures -ne 0 -or $evidence.errors -ne 0 -or $evidence.warHash -ne (Get-FileHash -LiteralPath $candidate).Hash) { throw 'Thiếu clean verify và WAR đúng cây code.' }
    if ($Target -eq 'Public') {
        $approval=Get-Content -LiteralPath (Join-Path $tools 'ui-approval.json') -Raw | ConvertFrom-Json
        if (!$approval.approved -or $approval.tree -ne $tree -or !$approval.reviewer) { throw 'Thiếu nghiệm thu UI của bản phát hành.' }
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($candidate)
    try {
        if (!$zip.GetEntry('assets/css/ui.css') -or $zip.GetEntry('assets/css/ui.css').Length -eq 0 -or !$zip.GetEntry('assets/icons/fa-sprite.svg')) { throw 'WAR thiếu CSS hoặc sprite UI.' }
    } finally { $zip.Dispose() }
}
if (Checked-Git @('diff','--name-only',$oldSha,$sha,'--','src/main/resources/db/migration','src/main/java/db/migration')) { throw 'Bản UI chứa thay đổi migration; phát hành schema riêng trước.' }
function Matches-Tomcat($Process) {
    $basePattern='(?i)(?:^|\s|")-Dcatalina\.base='+[regex]::Escape($base)+'(?=\s|"|$)'
    $homePattern='(?i)(?:^|\s|")-Dcatalina\.home='+[regex]::Escape($tomcatHome)+'(?=\s|"|$)'
    return $Process -and $Process.ExecutablePath -eq $java -and $Process.CommandLine -match $basePattern -and $Process.CommandLine -match $homePattern -and $Process.CommandLine.Contains('org.apache.catalina.startup.Bootstrap')
}
function Stop-UiTomcat([int]$ExpectedProcessId=0) {
    if ($ExpectedProcessId -eq 0) {
        $listeners=@(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
        $owners=@($listeners.OwningProcess | Select-Object -Unique)
        if ($owners.Count -ne 1) { throw 'Không xác minh được một Tomcat đang chạy.' }
        $ExpectedProcessId=$owners[0]
    }
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$ExpectedProcessId"
    if (!$process -and !(Get-Process -Id $ExpectedProcessId -ErrorAction SilentlyContinue)) { return }
    if (!(Matches-Tomcat $process)) { throw 'Không dừng tiến trình ngoài Tomcat đích.' }
    Stop-Process -Id $process.ProcessId
    for ($i=0;$i -lt 40;$i++) { if (!(Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue)) { return }; Start-Sleep -Milliseconds 250 }
    throw 'Tomcat chưa dừng; không thay WAR.'
}
function Start-UiTomcat([string]$ReleaseSha) {
    $cp=(Join-Path $tomcatHome 'bin/bootstrap.jar')+';'+(Join-Path $tomcatHome 'bin/tomcat-juli.jar')
    return Start-Process -FilePath $java -ArgumentList @('-Duser.timezone=Asia/Ho_Chi_Minh',"-Dcatalina.home=$tomcatHome","-Dcatalina.base=$base",("-Djava.io.tmpdir="+(Join-Path $base 'temp')),'-Dfile.encoding=UTF-8',"-Dapp.release.sha=$ReleaseSha",'-cp',$cp,'org.apache.catalina.startup.Bootstrap','start') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $base 'logs/stdout.log') -RedirectStandardError (Join-Path $base 'logs/stderr.log')
}
function Check-UiHealth([string]$ReleaseSha) {
    for ($i=0;$i -lt 45;$i++) {
        try { $health=Invoke-RestMethod "http://127.0.0.1:$port/health" -TimeoutSec 3; if ($health.status -eq 'UP' -and $health.database -eq 'UP' -and $health.release -eq $ReleaseSha) { return } } catch {}
        Start-Sleep -Seconds 1
    }
    throw 'Health bản UI không đạt.'
}
function Replace-UiWar([string]$Source) {
    Copy-Item -LiteralPath $Source -Destination $war -Force
    foreach ($relative in @('webapps/ROOT','work/Catalina/localhost/ROOT')) {
        $directory=Child-Path (Join-Path $base $relative) $base
        if (Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory -Recurse -Force }
    }
}
$keys=@('DB_URL','DB_USERNAME','DB_PASSWORD','MIGRATION_ENABLED','MIGRATION_USERNAME','MIGRATION_PASSWORD','APP_BASE_URL','APP_UPLOAD_DIR','MAIL_HOST','MAIL_PORT','MAIL_FROM','MAIL_USERNAME','MAIL_PASSWORD','MAIL_AUTH_ENABLED','MAIL_STARTTLS_ENABLED','MAIL_STARTTLS_REQUIRED')
$before=@{}; foreach ($key in $keys) { $before[$key]=[Environment]::GetEnvironmentVariable($key) }
try {
    $env:DB_URL="jdbc:mysql://127.0.0.1:$dbPort/sales_inventory?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false"
    $env:DB_USERNAME=$config.dbUsername; $env:DB_PASSWORD=$config.dbPassword; $env:MIGRATION_ENABLED='false'
    Remove-Item Env:MIGRATION_USERNAME,Env:MIGRATION_PASSWORD -ErrorAction SilentlyContinue
    $env:APP_BASE_URL=$config.appBaseUrl; $env:APP_UPLOAD_DIR=$uploads
    foreach ($field in @('host','port','from','username','password','authEnabled','starttlsEnabled','starttlsRequired')) {
        $envName='MAIL_'+([regex]::Replace($field,'([a-z])([A-Z])','$1_$2')).ToUpper()
        [Environment]::SetEnvironmentVariable($envName,[string]$config.('mail'+$field.Substring(0,1).ToUpper()+$field.Substring(1)))
    }
    $env:MAIL_PASSWORD=([string]$config.mailPassword) -replace '\s',''
    $classes=Join-Path $root 'target/ROOT/WEB-INF/classes'; $libraries=Join-Path $root 'target/ROOT/WEB-INF/lib/*'
    & $java '-Dfile.encoding=UTF-8' '-cp' "$classes;$libraries" 'vn.codegym.salesinventory.config.SchemaValidation'
    if ($LASTEXITCODE -ne 0) { throw 'Validate schema không đạt; chưa dừng web.' }
    Check-UiHealth $oldSha
    Write-Output "Preflight UI-only đạt: $Target, $sha; không chạy migration."
    if ($PreflightOnly) { return }
    if (!$RollbackWar) {
        $backup=Child-Path (Join-Path $releases ((Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+$sha.Substring(0,8))) $tools
        New-Item -ItemType Directory -Path $backup | Out-Null
        Copy-Item -LiteralPath $war -Destination (Join-Path $backup 'ROOT.war')
        Copy-Item -LiteralPath $deployment -Destination (Join-Path $backup 'deployment.json')
        Copy-Item -LiteralPath (Join-Path $base 'conf') -Destination (Join-Path $backup 'conf') -Recurse
        if (Test-Path -LiteralPath $uploads) { Copy-Item -LiteralPath $uploads -Destination (Join-Path $backup 'uploads') -Recurse }
        $dump='/tmp/ui-'+[Guid]::NewGuid().ToString('N')+'.sql'
        docker exec $container sh -c ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers --events --no-tablespaces sales_inventory > '+$dump)
        if ($LASTEXITCODE -ne 0) { throw 'Sao lưu DB thất bại; chưa dừng web.' }
        docker cp ($container+':'+$dump) (Join-Path $backup 'database.sql') | Out-Null
        if ($LASTEXITCODE -ne 0 -or (Get-Item -LiteralPath (Join-Path $backup 'database.sql')).Length -lt 100) { throw 'Backup DB không hợp lệ.' }
        docker exec $container rm -- $dump | Out-Null
        [pscustomobject]@{sha=$oldSha;target=$Target;base=$base;container=$container;warHash=(Get-FileHash -LiteralPath (Join-Path $backup 'ROOT.war')).Hash} | ConvertTo-Json | Set-Content (Join-Path $backup 'ui-backup.json') -Encoding UTF8
        $recoveryWar=Join-Path $backup 'ROOT.war'
    } else {
        $rollbackUndo=Child-Path (Join-Path $releases ('rollback-undo-'+[Guid]::NewGuid().ToString('N'))) $tools
        New-Item -ItemType Directory -Path $rollbackUndo | Out-Null
        $recoveryWar=Join-Path $rollbackUndo 'ROOT.war'
        Copy-Item -LiteralPath $war -Destination $recoveryWar
        [pscustomobject]@{sha=$oldSha;target=$Target;base=$base;container=$container;warHash=(Get-FileHash -LiteralPath $recoveryWar).Hash} | ConvertTo-Json | Set-Content (Join-Path $rollbackUndo 'ui-backup.json') -Encoding UTF8
    }
    Stop-UiTomcat
    $started=$false
    try {
        Replace-UiWar $candidate
        $process=Start-UiTomcat $sha; $started=$true
        Check-UiHealth $sha
        if ($InjectHealthFailure) { throw 'Mô phỏng lỗi health UI-only.' }
        if ($Target -eq 'Public') { $publicHealth=Invoke-RestMethod "$($config.appBaseUrl)/health"; if ($publicHealth.release -ne $sha -or $publicHealth.status -ne 'UP') { throw 'Health public không đúng SHA.' } }
        [pscustomobject]@{sha=$sha;tree=(Checked-Git @('rev-parse',($sha+'^{tree}')));warHash=(Get-FileHash -LiteralPath $war -Algorithm SHA256).Hash;pid=$process.Id;backup=$backup;target=$Target;uiOnly=$true;deployedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content $deployment -Encoding UTF8
        Write-Output "UI-only thành công: $sha; backup $backup."
    } catch {
        $failure=$_
        if ($started) { Stop-UiTomcat $process.Id }
        Replace-UiWar $recoveryWar
        $restored=Start-UiTomcat $oldSha
        Check-UiHealth $oldSha
        [pscustomobject]@{sha=$oldSha;tree=(Checked-Git @('rev-parse',($oldSha+'^{tree}')));warHash=(Get-FileHash -LiteralPath $war -Algorithm SHA256).Hash;pid=$restored.Id;backup=$backup;target=$Target;uiOnly=$true;restored=$true;deployedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content $deployment -Encoding UTF8
        Write-Output 'Đã phục hồi WAR cũ; giữ nguyên database, uploads và cấu hình.'
        throw $failure
    }
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key,$before[$key]) }
}
