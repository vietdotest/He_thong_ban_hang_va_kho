$ErrorActionPreference = 'Stop'

$tomcatVersion = '10.1.60'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.tools'
$tomcatRoot = Join-Path $toolsRoot "apache-tomcat-$tomcatVersion"
$archiveName = "apache-tomcat-$tomcatVersion-windows-x64.zip"
$archivePath = Join-Path $toolsRoot $archiveName
$downloadUrl = "https://dlcdn.apache.org/tomcat/tomcat-10/v$tomcatVersion/bin/$archiveName"
$checksumUrl = "$downloadUrl.sha512"

if (Test-Path -LiteralPath (Join-Path $tomcatRoot 'bin\catalina.bat')) {
    Write-Host "Tomcat is already available at $tomcatRoot"
    exit 0
}

New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null
Invoke-WebRequest -UseBasicParsing -Uri $downloadUrl -OutFile $archivePath
$checksumText = (Invoke-WebRequest -UseBasicParsing -Uri $checksumUrl).Content.Trim()
$expectedHash = ($checksumText -split '\s+')[0].ToLowerInvariant()
$stream = [System.IO.File]::OpenRead($archivePath)
try {
    $sha512 = [System.Security.Cryptography.SHA512]::Create()
    try {
        $actualHash = ([System.BitConverter]::ToString($sha512.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha512.Dispose()
    }
} finally {
    $stream.Dispose()
}
if ($actualHash -ne $expectedHash) {
    Remove-Item -LiteralPath $archivePath -Force
    throw 'Tomcat checksum verification failed.'
}

Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsRoot -Force
Remove-Item -LiteralPath $archivePath -Force

$randomBytes = New-Object byte[] 18
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$generator.GetBytes($randomBytes)
$generator.Dispose()
$managerPassword = [Convert]::ToBase64String($randomBytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
$usersFile = Join-Path $tomcatRoot 'conf\tomcat-users.xml'
$usersXml = Get-Content -LiteralPath $usersFile -Raw
$managerEntry = @"
  <role rolename="manager-script"/>
  <user username="netbeans-deployer" password="$managerPassword" roles="manager-script"/>
</tomcat-users>
"@
$usersXml = $usersXml.Replace('</tomcat-users>', $managerEntry)
Set-Content -LiteralPath $usersFile -Value $usersXml -Encoding UTF8

$credentialFile = Join-Path $toolsRoot 'netbeans-tomcat-credentials.txt'
Set-Content -LiteralPath $credentialFile -Encoding UTF8 -Value @(
    'Tomcat home: ' + $tomcatRoot,
    'Username: netbeans-deployer',
    'Password: ' + $managerPassword
)

Write-Host "Tomcat installed at $tomcatRoot"
Write-Host "NetBeans registration credentials are in $credentialFile"
