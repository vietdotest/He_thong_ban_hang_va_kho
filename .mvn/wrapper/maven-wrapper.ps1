$ErrorActionPreference = 'Stop'

$propertiesPath = Join-Path $PSScriptRoot 'maven-wrapper.properties'
$properties = @{}
Get-Content -LiteralPath $propertiesPath | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.+)$') {
        $properties[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$distributionUrl = $properties['distributionUrl']
$expectedHash = $properties['distributionSha512Sum']
$archiveName = [System.IO.Path]::GetFileName($distributionUrl)
$mavenDirectoryName = $archiveName -replace '-bin\.zip$', ''
$wrapperRoot = if ($env:MAVEN_USER_HOME) {
    Join-Path $env:MAVEN_USER_HOME 'wrapper\dists'
} else {
    Join-Path $env:USERPROFILE '.m2\wrapper\dists'
}
$installationDirectory = Join-Path $wrapperRoot $mavenDirectoryName
$mavenCommand = Join-Path $installationDirectory 'bin\mvn.cmd'

if (-not (Test-Path -LiteralPath $mavenCommand)) {
    New-Item -ItemType Directory -Force -Path $wrapperRoot | Out-Null
    $archivePath = Join-Path $wrapperRoot $archiveName
    Invoke-WebRequest -UseBasicParsing -Uri $distributionUrl -OutFile $archivePath
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
    if ($actualHash -ne $expectedHash.ToLowerInvariant()) {
        Remove-Item -LiteralPath $archivePath -Force
        throw 'Maven distribution checksum verification failed.'
    }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $wrapperRoot -Force
    Remove-Item -LiteralPath $archivePath -Force
}

& $mavenCommand @args
exit $LASTEXITCODE
