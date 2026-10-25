# Makes dist\ChillTV-USB-Kit.zip, the download for people who don't code: copy it to a USB stick, then follow
# START-HERE.html. Builds both apps with -Release first (no built-in remote key; every TV makes its own pairing code),
# so CHILLTV_RELEASE_KS and CHILLTV_RELEASE_PASS must be set (see home\build.ps1).
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $repo 'dist'
$kit = Join-Path $dist 'ChillTV-USB-Kit'

& (Join-Path $repo 'home\build.ps1') -Release
& (Join-Path $repo 'showcase\build.ps1') -Release
& cmd /c (Join-Path $repo 'remote\windows\build.cmd')
if ($LASTEXITCODE) { throw 'ChillRemote build failed' }

if (Test-Path $kit) { Remove-Item -Recurse -Force $kit }
New-Item -ItemType Directory -Force $kit, "$kit\PC helper (Windows)", "$kit\Remote for Windows" | Out-Null
Copy-Item (Join-Path $repo 'home\ChillTV-Home-release.apk') "$kit\ChillTV-Home.apk"
Copy-Item (Join-Path $repo 'showcase\ChillTV-Showcase-release.apk') "$kit\ChillTV-Showcase.apk"
Copy-Item (Join-Path $PSScriptRoot 'START-HERE.html') $kit
Copy-Item (Join-Path $PSScriptRoot 'windows\ChillTV-Helper.ps1'), (Join-Path $PSScriptRoot 'windows\Start Chill TV Helper.cmd') "$kit\PC helper (Windows)"
Copy-Item (Join-Path $repo 'remote\windows\ChillRemote.exe') "$kit\Remote for Windows"
Copy-Item (Join-Path $repo 'LICENSE') "$kit\LICENSE.txt"
# TV models: CleanYourTV's checked lists (MIT), built by installer\models\build_models.py into installer\models\catalog.
Copy-Item -Recurse (Join-Path $PSScriptRoot 'models\catalog') "$kit\TV models"

$zip = Join-Path $dist 'ChillTV-USB-Kit.zip'
Copy-Item (Join-Path $PSScriptRoot 'models\catalog\models.json') (Join-Path $dist 'models.json')
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path $kit -DestinationPath $zip
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
Set-Content (Join-Path $dist 'ChillTV-USB-Kit.zip.sha256') "$hash  ChillTV-USB-Kit.zip" -Encoding ascii
Write-Output "Built $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB) sha256 $hash"
