param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..')
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path $ProjectRoot).Path
$java = 'D:\DevEnv\Java\jdk21\bin\java.exe'
if (-not (Test-Path $java)) { $java = 'java' }

& $java '-Dfile.encoding=UTF-8' (Join-Path $PSScriptRoot 'GenLicenseKeys.java') $root
if ($LASTEXITCODE -ne 0) { throw "key generation failed" }
