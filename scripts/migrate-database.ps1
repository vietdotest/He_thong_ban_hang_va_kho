param([switch]$Run,[string]$JavaHomePath=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if(!$Run){Write-Output 'Không thay đổi database. Sau khi backup/kiểm tra đích, đặt DB_URL, DB_USERNAME, DB_PASSWORD, MIGRATION_USERNAME, MIGRATION_PASSWORD và chạy lại với -Run.';return}
foreach($key in @('DB_URL','DB_USERNAME','MIGRATION_USERNAME','MIGRATION_PASSWORD')){
 if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key))){throw "Thiếu cấu hình môi trường $key. Không dùng đích hoặc tài khoản mặc định."}
}
if($env:DB_URL -notmatch '^jdbc:mysql://[^/]+/[^?]+'){throw 'DB_URL phải là JDBC MySQL với database đích rõ ràng.'}
if($env:DB_USERNAME -eq $env:MIGRATION_USERNAME){throw 'Không dùng tài khoản runtime để chạy migration phát hành.'}
$projectRoot=Split-Path -Parent $PSScriptRoot
$classes=Join-Path $projectRoot 'target/ROOT/WEB-INF/classes'
$libraries=Join-Path $projectRoot 'target/ROOT/WEB-INF/lib/*'
$java=Join-Path $JavaHomePath 'bin/java.exe'
if(!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $classes)){throw 'Cần JDK 17 và bản WAR đã build.'}
$previousMigrationEnabled=$env:MIGRATION_ENABLED
try{
 $env:MIGRATION_ENABLED='true'
 & $java '-Dfile.encoding=UTF-8' '-cp' "$classes;$libraries" 'vn.codegym.salesinventory.config.SchemaMigration'
 if($LASTEXITCODE -ne 0){throw 'Migration chưa thành công. Không bật bản web mới; xem lỗi và giữ backup.'}
 Write-Output 'Migration hoàn tất. Tắt MIGRATION_ENABLED và bỏ thông tin tài khoản migration khỏi môi trường runtime trước khi bật web.'
}finally{$env:MIGRATION_ENABLED=$previousMigrationEnabled}
