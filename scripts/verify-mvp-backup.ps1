param([Parameter(Mandatory=$true)][string]$BackupDirectory,[switch]$Upgrade,[string]$CompatibilityWar='')
$ErrorActionPreference='Stop'
$backupRoot=[IO.Path]::GetFullPath($BackupDirectory)
$sqlFile=Join-Path $backupRoot 'database.sql'
if(!(Test-Path -LiteralPath $sqlFile) -or (Get-Item -LiteralPath $sqlFile).Length -lt 100){throw 'Thiếu SQL backup hợp lệ.'}
$taskRoot=Split-Path -Parent $PSScriptRoot
$temporaryName='codegym-mvp-restore-'+[Guid]::NewGuid().ToString('N')
$temporaryPassword=[Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N')
$temporaryId=$null
function Probe-Docker([string[]]$Arguments){$ErrorActionPreference='Continue';$captured=& docker.exe @Arguments 2>&1;return [pscustomobject]@{ExitCode=$LASTEXITCODE;Output=($captured|Out-String)}}
$environmentBefore=@{}
foreach($key in @('DB_URL','DB_USERNAME','DB_PASSWORD','MIGRATION_USERNAME','MIGRATION_PASSWORD','MIGRATION_ENABLED')){$environmentBefore[$key]=[Environment]::GetEnvironmentVariable($key)}
function Temp-Sql([string]$Statement){
    $result=$Statement | docker exec -i $temporaryId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 --protocol=TCP -uroot --batch --skip-column-names sales_inventory'
    if($LASTEXITCODE -ne 0){throw 'SQL trong database phục hồi tạm thất bại.'};return ($result -join "`n")
}
try{
    $temporaryId=(docker run -d --name $temporaryName --label nvdo.mvp.restore=temporary --tmpfs '/var/lib/mysql:rw,size=1g' -p '127.0.0.1::3306' -e ("MYSQL_ROOT_PASSWORD="+$temporaryPassword) -e MYSQL_DATABASE=sales_inventory mysql:8.4.11 --log-bin-trust-function-creators=1 --innodb-flush-log-at-trx-commit=2 --sync-binlog=0).Trim()
    if($LASTEXITCODE -ne 0 -or $temporaryId -notmatch '^[a-f0-9]{64}$'){throw 'Không tạo được container phục hồi tạm.'}
    $ready=$false
    for($attempt=0;$attempt -lt 90;$attempt++){$probe=Probe-Docker @('exec',$temporaryId,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 --protocol=TCP -uroot -e SELECT/**/1');if($probe.ExitCode -eq 0){$ready=$true;break};Start-Sleep -Milliseconds 1000}
    if(!$ready){throw 'Database phục hồi tạm chưa sẵn sàng.'}
    docker cp $sqlFile ($temporaryId+':/tmp/restore.sql') | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Không chuyển được backup SQL sang đích tạm.'}
    docker exec $temporaryId sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 --protocol=TCP -uroot sales_inventory < /tmp/restore.sql' | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Backup không phục hồi được.'}
    $before=Temp-Sql 'SELECT COUNT(*),COALESCE(SUM(net_total),0) FROM orders; SELECT COUNT(*),COALESCE(SUM(net),0),COALESCE(SUM(base_quantity),0) FROM order_lines; SELECT COUNT(*) FROM users; SELECT COUNT(*) FROM audit_logs;'
    $schemaBefore=Temp-Sql 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;'
    if($Upgrade){
        Temp-Sql ("CREATE USER 'mvp_runtime'@'%' IDENTIFIED BY '"+$temporaryPassword+"'; GRANT SELECT,INSERT,UPDATE,DELETE ON sales_inventory.* TO 'mvp_runtime'@'%'; CREATE USER 'mvp_migration'@'%' IDENTIFIED BY '"+$temporaryPassword+"'; GRANT ALL ON sales_inventory.* TO 'mvp_migration'@'%';") | Out-Null
        $temporaryInfo=docker inspect $temporaryId | ConvertFrom-Json
        $temporaryPort=$temporaryInfo[0].NetworkSettings.Ports.'3306/tcp'[0].HostPort
        $env:DB_URL="jdbc:mysql://127.0.0.1:$temporaryPort/sales_inventory?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false"
        $env:DB_USERNAME='mvp_runtime';$env:DB_PASSWORD=$temporaryPassword;$env:MIGRATION_USERNAME='mvp_migration';$env:MIGRATION_PASSWORD=$temporaryPassword
        & (Join-Path $PSScriptRoot 'migrate-database.ps1') -Run -JavaHomePath 'C:\Program Files\Java\jdk-17'
        $after=Temp-Sql 'SELECT COUNT(*),COALESCE(SUM(net_total),0) FROM orders; SELECT COUNT(*),COALESCE(SUM(net),0),COALESCE(SUM(base_quantity),0) FROM order_lines; SELECT COUNT(*) FROM users; SELECT COUNT(*) FROM audit_logs;'
        if($after -ne $before){throw 'Nâng cấp bản phục hồi làm lệch số dòng, lượng hoặc tổng tiền lịch sử.'}
        $env:MIGRATION_ENABLED='false';Remove-Item Env:MIGRATION_USERNAME,Env:MIGRATION_PASSWORD -ErrorAction SilentlyContinue
        $classes=Join-Path $taskRoot 'target/ROOT/WEB-INF/classes';$libraries=Join-Path $taskRoot 'target/ROOT/WEB-INF/lib/*'
        & 'C:\Program Files\Java\jdk-17\bin\java.exe' '-Dfile.encoding=UTF-8' '-cp' "$classes;$libraries" 'vn.codegym.salesinventory.config.SchemaValidation'
        if($LASTEXITCODE -ne 0){throw 'Runtime chỉ validate không đạt trên database nâng cấp.'}
    }
    $compatibilityHash=''
    if($CompatibilityWar) {
        if(!$Upgrade){throw 'Kiểm tương thích WAR cũ cần nâng cấp trên bản phục hồi tạm.'}
        $compatibilityHash=(Get-FileHash -LiteralPath $CompatibilityWar -Algorithm SHA256).Hash
        $compatibilityRoot=[IO.Path]::GetFullPath((Join-Path $backupRoot ('compatibility-'+[Guid]::NewGuid().ToString('N'))))
        if(!$compatibilityRoot.StartsWith($backupRoot.TrimEnd('\','/')+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Đích giải nén nằm ngoài backup.'}
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::ExtractToDirectory([IO.Path]::GetFullPath($CompatibilityWar),$compatibilityRoot)
        $compatibilityClasses=Join-Path $compatibilityRoot 'WEB-INF/classes';$compatibilityLib=Join-Path $compatibilityRoot 'WEB-INF/lib/*'
        & 'C:\Program Files\Java\jdk-17\bin\java.exe' '-Dfile.encoding=UTF-8' '-cp' "$compatibilityClasses;$compatibilityLib" 'vn.codegym.salesinventory.config.SchemaValidation'
        if($LASTEXITCODE -ne 0){throw 'WAR cũ không validate được schema mới; chưa cho phép phát hành.'}
    }
    $schemaAfter=Temp-Sql 'SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;'
    [pscustomobject]@{passed=$true;backupHash=(Get-FileHash -LiteralPath $sqlFile -Algorithm SHA256).Hash;schemaBefore=$schemaBefore;schemaAfter=$schemaAfter;preserved=$before;upgraded=[bool]$Upgrade;compatibilityWarHash=$compatibilityHash;oldRuntimeValidated=[bool]$CompatibilityWar;checkedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backupRoot 'restore-verification.json') -Encoding utf8
    Write-Output "Đã kiểm chứng phục hồi SQL trên container tạm, schema $schemaBefore -> $schemaAfter; lịch sử và số tiền giữ nguyên."
}finally{
    foreach($key in $environmentBefore.Keys){[Environment]::SetEnvironmentVariable($key,$environmentBefore[$key])}
    if($temporaryId -and $temporaryId -match '^[a-f0-9]{64}$'){
        $cleanupInfo=docker inspect $temporaryId | ConvertFrom-Json
        if($LASTEXITCODE -eq 0 -and $cleanupInfo[0].Name -eq ('/'+$temporaryName) -and $cleanupInfo[0].Config.Labels.'nvdo.mvp.restore' -eq 'temporary' -and @($cleanupInfo[0].Mounts | Where-Object {$_.Type -in @('bind','volume')}).Count -eq 0){docker rm -f $temporaryId | Out-Null}
    }
}
