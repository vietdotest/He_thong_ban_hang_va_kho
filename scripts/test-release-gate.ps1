$ErrorActionPreference='Stop'
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('codegym-release-gate-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$tree='0123456789012345678901234567890123456789';$warHash='ABC123'
$artifacts=@()
foreach($kind in @('backend','ui','http')){
    $name=$kind+'.json';$path=Join-Path $testRoot $name
    @{passed=$true;tree=$tree} | ConvertTo-Json | Set-Content -LiteralPath $path -Encoding utf8
    $artifacts+=@{path=$name;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
}
$check=@{passed=$true;mode='delegated';tree=$tree;warHash=$warHash;visualInspectionPassed=$true;schemaRollbackCompatible=$true;artifacts=$artifacts}
$checkPath=Join-Path $testRoot 'business-release-check.json'
function Save-Check { $check | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $checkPath -Encoding utf8 }
function Assert-Denied([scriptblock]$Action){$denied=$false;try{& $Action}catch{$denied=$true};if(!$denied){throw 'Cổng phát hành cho phép bằng chứng không hợp lệ.'}}
Save-Check
& (Join-Path $PSScriptRoot 'assert-business-release.ps1') -Tree $tree -EvidenceDirectory $testRoot -WarHash $warHash
Assert-Denied { & (Join-Path $PSScriptRoot 'assert-business-release.ps1') -Tree 'other-tree' -EvidenceDirectory $testRoot -WarHash $warHash }
Assert-Denied { & (Join-Path $PSScriptRoot 'assert-business-release.ps1') -Tree $tree -EvidenceDirectory $testRoot -WarHash 'other-war' }
$check.visualInspectionPassed=$false;Save-Check
Assert-Denied { & (Join-Path $PSScriptRoot 'assert-business-release.ps1') -Tree $tree -EvidenceDirectory $testRoot -WarHash $warHash }
$check.visualInspectionPassed=$true;$check.schemaRollbackCompatible=$false;Save-Check
Assert-Denied { & (Join-Path $PSScriptRoot 'assert-business-release.ps1') -Tree $tree -EvidenceDirectory $testRoot -WarHash $warHash }
$check.schemaRollbackCompatible=$true;Save-Check
Add-Content -LiteralPath (Join-Path $testRoot 'http.json') -Value 'changed'
Assert-Denied { & (Join-Path $PSScriptRoot 'assert-business-release.ps1') -Tree $tree -EvidenceDirectory $testRoot -WarHash $warHash }
$artifacts[2].path='../outside.json';Save-Check
Assert-Denied { & (Join-Path $PSScriptRoot 'assert-business-release.ps1') -Tree $tree -EvidenceDirectory $testRoot -WarHash $warHash }
Write-Output 'PASS: 7 ca kiểm cổng phát hành, không triển khai hoặc thay đổi dữ liệu.'
