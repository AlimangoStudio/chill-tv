# Chill TV helper for Windows: installs Chill TV on an Android TV box over Wi-Fi, makes it the home screen, and can
# clean up ads and bloat with the checked list for your TV model. Started by "Start Chill TV Helper.cmd".
#
# Nothing is installed on this PC except Google's adb tool, downloaded from Google on first use into
# %LOCALAPPDATA%\ChillTV\platform-tools (delete that folder to remove it). Apps on the TV are only switched off, never
# removed, and every switch-off is written to %LOCALAPPDATA%\ChillTV\changes-<TV>.txt so Undo turns exactly those back on.
#
# TV model lists: "TV models" in the kit, from CleanYourTV (https://github.com/Gathin23/cleanyourtv, MIT licence).
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$kit = Split-Path -Parent $here
$state = Join-Path $env:LOCALAPPDATA 'ChillTV'
$adb = Join-Path $state 'platform-tools\adb.exe'
$catalogPath = Join-Path $kit 'TV models\models.json'
$apps = @{
    home     = @{ name = 'Chill TV Home';     pkg = 'app.alimango.chilltv.home';     apk = 'ChillTV-Home.apk' }
    showcase = @{ name = 'Chill TV Showcase'; pkg = 'app.alimango.chilltv.showcase'; apk = 'ChillTV-Showcase.apk' }
}
# Home screens found on many boxes; your model's own launcher packages are added from its list.
$commonLaunchers = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.android.tv.launcher',
    'com.google.android.leanbacklauncher', 'com.mitv.tvhome.atv', 'com.mitv.tvhome.michannel', 'com.mitv.tvhome.mitvplus')
# A package name goes into a shell command on the TV: only bare names are ever sent.
$packageName = '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'

function Say($text, $color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Pause-Key { Write-Host ''; Read-Host 'Press Enter to continue' | Out-Null }
function Ask-Yes($question) { return ((Read-Host "$question (Y/N)").Trim().ToUpper() -eq 'Y') }

function Get-Adb {
    if (Test-Path $adb) { return }
    Say 'Getting Google''s adb tool (about 7 MB, once)...' Cyan
    $zip = Join-Path $env:TEMP 'chilltv-platform-tools.zip'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
    New-Item -ItemType Directory -Force $state | Out-Null
    Expand-Archive $zip -DestinationPath $state -Force
    Remove-Item $zip -Force
    if (-not (Test-Path $adb)) { throw 'adb.exe was not found after the download.' }
}

function Invoke-Adb([string[]]$a) {
    # adb writes ordinary answers ("no such device", "failed to connect") to stderr. In Windows PowerShell 5.1 that
    # becomes an error record, which 'Stop' turns into an abort, so read it as text here and decide from the words.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return ((& $adb @a 2>&1 | ForEach-Object { "$_" }) -join "`n").Trim() }
    finally { $ErrorActionPreference = $previous }
}
function Tv([string[]]$a) { return Invoke-Adb (@('-s', $script:target) + $a) }

function Wait-Device([string]$target, [int]$seconds) {
    for ($i = 0; $i -lt $seconds; $i += 2) {
        if ((Invoke-Adb @('-s', $target, 'get-state')) -eq 'device') { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# Chill TV needs Android TV or Google TV. LG (webOS), Samsung (Tizen), VIDAA and Coolita TVs can't install Android apps.
function Check-Tv {
    Say ''
    Say 'Chill TV works on Android TV and Google TV: Sony, TCL, Xiaomi / Mi Box, Philips, Sharp, Toshiba, and' White
    Say 'Hisense, Devant, Coocaa, Skyworth, XTREME or ACE models that say "Google TV" or "Android TV".' White
    Say 'It can''t run on LG (webOS), Samsung (Tizen), VIDAA or Coolita TVs.'
    Say 'Quick check: does the TV have the Google Play Store app?'
    if (Ask-Yes 'Does this TV (or the box plugged into it) have Google Play Store?') { return $true }
    Say ''
    Say 'Then this TV can''t install Chill TV (or any Android app).' Yellow
    Say 'Plug a Google TV or Android TV stick or box (for example Chromecast with Google TV or Mi TV Stick) into a' Yellow
    Say 'spare HDMI port, then run this helper again for the stick.' Yellow
    Say 'Not sure? Look in the TV''s Settings > About for "Android TV" or "Google TV".'
    return (Ask-Yes 'Try to connect anyway?')
}

function Connect-Tv {
    Say ''
    Say 'Before you start, on the TV:' White
    Say '  1. Settings > Device Preferences > About > press OK on "Build" 7 times (Developer options appear).'
    Say '  2. Settings > Device Preferences > Developer options > turn on "USB debugging".'
    Say '  3. The TV and this PC must be on the same Wi-Fi.'
    Say 'The TV address is in Chill TV (Settings > Remote), or in the TV''s Wi-Fi settings (IP address).'
    $ip = (Read-Host 'TV address (for example 192.168.1.50)').Trim()
    if ($ip -notmatch '^[0-9A-Za-z.\-:]+$') { throw 'That does not look like a TV address.' }
    $script:tvName = $ip
    $script:target = "${ip}:5555"
    Invoke-Adb @('disconnect', $script:target) | Out-Null
    Say (Invoke-Adb @('connect', $script:target))
    Say 'Look at the TV: if it asks "Allow USB debugging?", tick "Always allow" and choose OK.' Yellow
    if (Wait-Device $script:target 40) { Say "Connected to the TV at $ip." Green; return }

    # Android 11+ and Google TV: Wi-Fi debugging may need a pairing code first.
    Say ''
    Say 'The TV did not answer on the usual port. Newer TVs need pairing once:' White
    Say '  On the TV: Developer options > Wireless debugging > turn it on > "Pair device with pairing code".'
    $pairAt = (Read-Host 'Type the address:port shown under the pairing code (for example 192.168.1.50:37123)').Trim()
    $code = (Read-Host 'Type the 6-digit pairing code').Trim()
    if ($pairAt -notmatch '^[0-9A-Za-z.\-]+:\d+$' -or $code -notmatch '^\d{6}$') { throw 'The address or code is not in the expected form.' }
    Say (Invoke-Adb @('pair', $pairAt, $code))
    $connectAt = (Read-Host 'Now type the address:port shown on the Wireless debugging screen (not the pairing one)').Trim()
    if ($connectAt -notmatch '^[0-9A-Za-z.\-]+:\d+$') { throw 'That address is not in the expected form.' }
    $script:target = $connectAt
    Say (Invoke-Adb @('connect', $script:target))
    if (-not (Wait-Device $script:target 20)) {
        throw "Could not connect to $connectAt. Check that the TV and this PC are on the same Wi-Fi and USB debugging is on. LG, Samsung, VIDAA and Coolita TVs have no USB debugging: they can't run Chill TV."
    }
    Say "Connected to the TV at $connectAt." Green
}

function Choose-Model {
    $script:model = $null
    if (-not (Test-Path $catalogPath)) { return }
    $catalog = Get-Content $catalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $brand = (Tv @('shell', 'getprop', 'ro.product.brand')).ToLower()
    $words = @((Tv @('shell', 'getprop', 'ro.product.model')), (Tv @('shell', 'getprop', 'ro.product.device'))) |
        ForEach-Object { $_.ToLower() } | Where-Object { $_.Length -ge 3 }
    Say ''
    Say "This TV says it is: $((Tv @('shell', 'getprop', 'ro.product.manufacturer'))) $((Tv @('shell', 'getprop', 'ro.product.model'))) (Android $((Tv @('shell', 'getprop', 'ro.build.version.release'))))" White
    $found = @($catalog.models | Where-Object {
            $text = ($_.id + ' ' + $_.name).ToLower()
            ($words | Where-Object { $text.Contains($_) }).Count -gt 0
        } | Select-Object -First 8)
    if ($found.Count -eq 0 -and $brand) { $found = @($catalog.models | Where-Object { $_.brand.ToLower() -eq $brand } | Select-Object -First 8) }
    # The entry for this TV's Android version first (one box often has a list per firmware).
    $release = (Tv @('shell', 'getprop', 'ro.build.version.release')).Trim()
    $found = @($found | Sort-Object { if ($release -and $_.os -match "$([regex]::Escape($release))") { 0 } else { 1 } })
    while ($true) {
        Say 'Which TV model is it? (the cleanup list and the home-screen switch depend on it)' White
        for ($i = 0; $i -lt $found.Count; $i++) { Say ("  {0}  {1}  [{2}]" -f ($i + 1), $found[$i].name, $found[$i].os) }
        Say '  S  Search the list by name'
        Say '  0  My model is not listed (general steps, no cleanup list)'
        $pick = (Read-Host 'Your choice').Trim()
        if ($pick -eq '0') { return }
        if ($pick.ToUpper() -eq 'S') {
            $q = (Read-Host 'Type part of the name (for example "Mi Box" or "TCL 55")').Trim().ToLower()
            $found = @($catalog.models | Where-Object { ($_.name + ' ' + $_.brand).ToLower().Contains($q) } | Select-Object -First 12)
            if ($found.Count -eq 0) { Say 'Nothing found; try a shorter name.' Yellow }
            continue
        }
        $n = 0
        if ([int]::TryParse($pick, [ref]$n) -and $n -ge 1 -and $n -le $found.Count) {
            $script:model = $found[$n - 1]
            Say "Model: $($script:model.name)" Green
            if ($script:model.notes) { Say "Notes from people with this TV: $($script:model.notes)" }
            if ($script:model.fit -eq 'apps') { Say 'Fire TV keeps Amazon''s own home screen: Chill TV installs and opens from Apps.' Yellow }
            return
        }
        Say 'Please choose one of the numbers, S or 0.' Yellow
    }
}

function Changes-File { return Join-Path $state ("changes-" + ($script:tvName -replace '[^0-9A-Za-z.\-]', '_') + ".txt") }

function Disable-Packages([string[]]$names, [string]$why) {
    $installed = Tv @('shell', 'pm', 'list', 'packages', '-e')
    $file = Changes-File
    $done = 0
    foreach ($p in ($names | Select-Object -Unique)) {
        if ($p -notmatch $packageName) { continue }
        if ($installed -notmatch "package:$([regex]::Escape($p))(\r?\n|$)") { continue }
        $r = Tv @('shell', 'pm', 'disable-user', '--user', '0', $p)
        if ($r -match 'new state: disabled') {
            Add-Content $file $p -Encoding ascii
            $done++
        } else {
            Say "  could not switch off $p ($r)" Yellow
        }
    }
    Say "Switched off $done app(s): $why. Undo turns them back on." Green
}

function Install-App($app) {
    $apk = Join-Path $kit $app.apk
    if (-not (Test-Path $apk)) { throw "$($app.apk) is not in the kit folder ($kit)." }
    Say "Installing $($app.name)..." Cyan
    $r = Tv @('install', '-r', $apk)
    if ($r -notmatch 'Success') { throw "Install failed: $r" }
    Say "$($app.name) installed." Green
}

function Set-Home($app) {
    if ($script:model -and $script:model.fit -eq 'apps') {
        Say 'This is a Fire TV: Amazon keeps its own home screen. Open Chill TV from Apps (or "Your Apps & Channels").' Yellow
        return
    }
    Say "Home screen: $(Tv @('shell', 'cmd', 'package', 'set-home-activity', $app.pkg))"
    Tv @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
    Say "Press the Home button on the TV remote: $($app.name) should open." Green
    Say 'If the TV''s old home screen still opens, choose 3 (make it stick).'
}

function Make-Stick($app) {
    $names = @($commonLaunchers)
    if ($script:model) { $names += @($script:model.home_packages) }
    Tv @('shell', 'cmd', 'package', 'set-home-activity', $app.pkg) | Out-Null
    Disable-Packages $names "the TV's own home screen"
    Tv @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
}

function Clean-Up {
    if (-not $script:model) { Say 'Choose your TV model first (option 6). Without a checked list, nothing is switched off.' Yellow; return }
    $lists = @($script:model.lists)
    $list = $lists[0]
    if ($lists.Count -gt 1) {
        for ($i = 0; $i -lt $lists.Count; $i++) { Say ("  {0}  {1} ({2} apps)" -f ($i + 1), $lists[$i].name, $lists[$i].count) }
        $n = 0
        if ([int]::TryParse((Read-Host 'Which list?').Trim(), [ref]$n) -and $n -ge 1 -and $n -le $lists.Count) { $list = $lists[$n - 1] }
    }
    $path = Join-Path (Join-Path $kit 'TV models') $list.file
    $names = Get-Content $path -Encoding UTF8 | ForEach-Object { ($_ -split '#', 2)[0].Trim() } | Where-Object { $_ }
    Say ''
    Say "The $($script:model.name) list switches off $($names.Count) ads, tracking and preinstalled apps that people with this TV checked." White
    Say 'Nothing is uninstalled, and Undo turns everything back on. Open the list to read each line first:'
    Say "  $path"
    if (-not (Ask-Yes 'Switch them off now?')) { return }
    Disable-Packages $names 'ads and bloat'
    Say 'Restart the TV once (Settings > Device Preferences > About > Restart).'
}

function Undo-All {
    $file = Changes-File
    if (Test-Path $file) {
        $names = Get-Content $file | Where-Object { $_ -match $packageName } | Select-Object -Unique
        foreach ($p in $names) {
            $r = Tv @('shell', 'pm', 'enable', $p)
            if ($r -match 'new state: enabled') { Say "  switched back on: $p" }
        }
        Move-Item $file "$file.undone-$(Get-Date -Format yyyyMMdd-HHmmss)" -Force
    } else {
        Say 'Nothing was switched off on this TV by this helper (or it was on another PC).'
    }
    $stock = @($commonLaunchers) + $(if ($script:model) { @($script:model.home_packages) } else { @() })
    $back = $stock | Where-Object { $_ -match $packageName -and (Tv @('shell', 'pm', 'path', $_)) -match 'package:' } | Select-Object -First 1
    if ($back) { Tv @('shell', 'cmd', 'package', 'set-home-activity', $back) | Out-Null; Say "Home screen set back to $back." Green }
    Say 'Chill TV stays installed; uninstall it in Settings > Apps if you want.'
}

try {
    $host.UI.RawUI.WindowTitle = 'Chill TV Helper'
    Say 'Chill TV Helper' White
    Say '==============='
    New-Item -ItemType Directory -Force $state | Out-Null
    Get-Adb
    if (-not (Check-Tv)) { Say 'Nothing was changed.' Green; Pause-Key; exit }
    Connect-Tv
    Choose-Model
    $quit = $false
    while (-not $quit) {
        Say ''
        Say "What would you like to do?$(if ($script:model) { "  (model: $($script:model.name))" })" White
        Say '  1  Install Chill TV Home and make it the home screen (family TV)'
        Say '  2  Install Chill TV Showcase and make it the home screen (shop, clinic or office TV)'
        Say '  3  Make it stick: also switch off the TV''s own home screen (if Home still opens the old one)'
        Say '  4  Clean up ads and bloat with the checked list for this TV model'
        Say '  5  Undo: turn back on everything this helper switched off, and the TV''s own home screen'
        Say '  6  Choose the TV model again'
        Say '  7  Quit'
        switch ((Read-Host 'Type 1-7 and press Enter').Trim()) {
            '1' { Install-App $apps.home; Set-Home $apps.home; Pause-Key }
            '2' { Install-App $apps.showcase; Set-Home $apps.showcase; Pause-Key }
            '3' {
                $app = if ((Read-Host 'Which one is your home screen? Type H for Home or S for Showcase').Trim().ToUpper() -eq 'S') { $apps.showcase } else { $apps.home }
                Make-Stick $app; Pause-Key
            }
            '4' { Clean-Up; Pause-Key }
            '5' { Undo-All; Pause-Key }
            '6' { Choose-Model }
            '7' { $quit = $true }
            default { Say 'Please type a number from 1 to 7.' Yellow }
        }
    }
    Invoke-Adb @('disconnect', $script:target) | Out-Null
    Say 'Done. You can turn "USB debugging" off again in the TV''s Developer options.' Green
} catch {
    Say ''
    Say "Something went wrong: $($_.Exception.Message)" Red
    Pause-Key
}
