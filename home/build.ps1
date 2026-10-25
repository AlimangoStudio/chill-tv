# Builds ChillTV-Home.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs the Android SDK (build-tools 34.0.0, platform android-34) and JDK 17.
# -Release: the public download. No remote key is built in (every TV makes its own pairing code, PairingCode.java),
# and the APK is signed with the release keystore named by CHILLTV_RELEASE_KS (password in CHILLTV_RELEASE_PASS),
# kept outside the repo. Without -Release this is a self-build with your own key, as before.
param([switch]$Release)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = "$sdk\build-tools\34.0.0"
$jar = "$sdk\platforms\android-34\android.jar"
# JDK 17: set JAVA_HOME first, or this picks the newest Eclipse Adoptium JDK 17.
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-17*' | Sort-Object Name | Select-Object -Last 1).FullName }
$jdk = "$env:JAVA_HOME\bin"
$out = "$root\build"

# Remote key: kept in remote-key.txt (out of git), made on the first build. The source keeps the CHANGE-ME placeholder.
if (-not $Release) {
$keyFile = "$root\remote-key.txt"
if (-not (Test-Path $keyFile)) {
    $new = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    Set-Content $keyFile $new -Encoding ascii
    Write-Output "Remote key created in remote-key.txt: $new"
}
$remoteKey = (Get-Content $keyFile -Raw).Trim()
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

Copy-Item -Recurse "$root\app\res" "$out\res"
$rx = "$out\res\values\remote.xml"
if (-not $Release) { (Get-Content $rx -Raw).Replace('CHANGE-ME', $remoteKey) | Set-Content $rx -NoNewline -Encoding utf8 }
& "$bt\aapt2.exe" compile --dir "$out\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
& "$bt\aapt2.exe" link -I $jar --manifest "$root\app\AndroidManifest.xml" -A "$root\app\assets" `
    --java "$out\gen" --min-sdk-version 21 --target-sdk-version 31 --version-code 1 --version-name 1.0 `
    -o "$out\unsigned.apk" "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$src = Get-ChildItem -Recurse "$root\app\src", "$out\gen" -Filter *.java | ForEach-Object { $_.FullName }
$srcList = ($src | ForEach-Object { "`"$_`"" }) -join ' '
cmd /c "`"$jdk\javac.exe`" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath `"$jar`" -d `"$out\classes`" $srcList 2>&1" |
    Where-Object { $_ -notmatch '^Note:' }
if ($LASTEXITCODE) { throw 'javac failed' }

$classes = Get-ChildItem -Recurse "$out\classes" -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --release --lib $jar --min-api 21 --output "$out\dex" $classes
if ($LASTEXITCODE) { throw 'd8 failed' }

Push-Location "$out\dex"
& "$jdk\jar.exe" uf "$out\unsigned.apk" classes.dex
Pop-Location

& "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

# Your own signing key, made on the first build and kept out of git. Keep it: updates must be signed with the same key.
$ks = "$root\signing.keystore"
$pass = if ($env:CHILLTV_KEY_PASS) { $env:CHILLTV_KEY_PASS } else { 'chilltv' }
$apk = "$root\ChillTV-Home.apk"
if ($Release) {
    if (-not $env:CHILLTV_RELEASE_KS -or -not (Test-Path $env:CHILLTV_RELEASE_KS) -or -not $env:CHILLTV_RELEASE_PASS) { throw 'Release builds need CHILLTV_RELEASE_KS and CHILLTV_RELEASE_PASS' }
    $ks = $env:CHILLTV_RELEASE_KS; $pass = $env:CHILLTV_RELEASE_PASS
    $apk = "$root\ChillTV-Home-release.apk"
} elseif (-not (Test-Path $ks)) {
    cmd /c "`"$jdk\keytool.exe`" -genkeypair -keystore `"$ks`" -alias chilltv -keyalg RSA -keysize 2048 -validity 10000 -storepass $pass -keypass $pass -dname `"CN=Chill TV`" 2>&1" | Out-Null
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass "pass:$pass" --key-pass "pass:$pass" --out $apk "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
Write-Output "Built $apk"
