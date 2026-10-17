param([Parameter(Mandatory=$true)][string]$Tree,[Parameter(Mandatory=$true)][string]$EvidenceDirectory,[Parameter(Mandatory=$true)][string]$WarHash)
$ErrorActionPreference='Stop'
$check=Get-Content -LiteralPath (Join-Path $EvidenceDirectory 'business-release-check.json') -Raw | ConvertFrom-Json
if (!$check.passed -or $check.mode -ne 'delegated' -or $check.tree -ne $Tree -or $check.warHash -ne $WarHash -or !$check.visualInspectionPassed -or !$check.schemaRollbackCompatible) {
    throw 'Thiếu kết quả kiểm phát hành đúng cây code, WAR, giao diện hoặc rollback.'
}
if (@($check.artifacts).Count -lt 3) { throw 'Thiếu bằng chứng backend, UI hoặc luồng HTTP.' }
foreach ($artifact in $check.artifacts) {
    $path=[IO.Path]::GetFullPath((Join-Path $EvidenceDirectory $artifact.path))
    $boundary=[IO.Path]::GetFullPath($EvidenceDirectory).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
    if (!$path.StartsWith($boundary,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $artifact.sha256) { throw 'Bằng chứng phát hành thiếu hoặc đã thay đổi.' }
    $result=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    if (!$result.passed -or $result.tree -ne $Tree) { throw 'Bằng chứng không đạt hoặc không đúng cây code.' }
}
