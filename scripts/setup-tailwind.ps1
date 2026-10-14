param()
$ErrorActionPreference = 'Stop'
$version = '4.3.3'
$root = Split-Path -Parent $PSScriptRoot
$name = 'tailwindcss-windows-x64.exe'
if (![Environment]::Is64BitOperatingSystem) { throw 'Bản build này yêu cầu Windows 64 bit.' }
$expected = ((Get-Content -LiteralPath (Join-Path $PSScriptRoot 'tailwind.sha256') | Where-Object { $_.EndsWith('  '+$name) }) -split '\s+')[0]
$directory = Join-Path $root ".tools/tailwind/$version"
$binary = Join-Path $directory $name
New-Item -ItemType Directory -Force -Path $directory | Out-Null
if (!(Test-Path -LiteralPath $binary) -or (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash -ne $expected) {
    $download = Join-Path $directory ($name + '.download')
    Invoke-WebRequest -Uri "https://github.com/tailwindlabs/tailwindcss/releases/download/v$version/$name" -OutFile $download
    if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne $expected) { throw 'SHA-256 Tailwind không đúng; không sử dụng binary tải về.' }
    Move-Item -LiteralPath $download -Destination $binary -Force
}
Write-Output "Tailwind $version đã xác minh SHA-256."
