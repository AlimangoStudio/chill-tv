# Chill TV helper for Windows: installs Chill TV on an Android TV box over Wi-Fi and makes it the home screen.
# Started by "Start Chill TV Helper.cmd". Nothing is installed on this PC except Google's adb tool, which is downloaded
# from Google on first use into %LOCALAPPDATA%\ChillTV\platform-tools (delete that folder to remove it).
#
# The USB-stick route (START-HERE.html) installs the apps without a PC. This helper is for the one step a USB stick
# cannot do on most Android TV boxes: making Chill TV the home screen. Every change has an undo (option 4).
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$kit = Split-Path -Parent $here
$tools = Join-Path $env:LOCALAPPDATA 'ChillTV\platform-tools'
$adb = Join-Path $tools 'adb.exe'
$apps = @{
    home     = @{ name = 'Chill TV Home';     pkg = 'app.alimango.chilltv.home';     apk = 'ChillTV-Home.apk' }
    showcase = @{ name = 'Chill TV Showcase'; pkg = 'app.alimango.chilltv.showcase'; apk = 'ChillTV-Showcase.apk' }
}
# The TV's own home screens. They are switched off (never removed) only when you choose "make it stick", and option 4
# switches them back on.
$stockLaunchers = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.mitv.tvhome.atv',
    'com.mitv.tvhome.michannel', 'com.mitv.tvhome.mitvplus', 'com.android.tv.launcher', 'com.google.android.leanbacklauncher')

function Say($text, $color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Pause-Key { Write-Host ''; Read-Host 'Press Enter to continue' | Out-Null }

function Get-Adb {
    if (Test-Path $adb) { return }
    Say 'Getting Google''s adb tool (about 7 MB, once)...' Cyan
    $zip = Join-Path $env:TEMP 'chilltv-platform-tools.zip'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
    $dest = Join-Path $env:LOCALAPPDATA 'ChillTV'
    New-Item -ItemType Directory -Force $dest | Out-Null
    Expand-Archive $zip -DestinationPath $dest -Force
    Remove-Item $zip -Force
    if (-not (Test-Path $adb)) { throw 'adb.exe was not found after the download.' }
}

function Invoke-Adb([string[]]$a) {
    $out = & $adb @a 2>&1 | Out-String
    return $out.Trim()
}

function Connect-Tv {
    Say ''
    Say 'Before you start, on the TV:' White
    Say '  1. Settings > Device Preferences > About > press OK on "Build" 7 times (Developer options appear).'
    Say '  2. Settings > Device Preferences > Developer options > turn on "USB debugging".'
    Say '  3. The TV and this PC must be on the same Wi-Fi.'
    Say ''
    Say 'The TV address is in Chill TV (Settings > Remote, or the phone icon in Showcase),'
    Say 'or in the TV''s Settings > Network & Internet > your Wi-Fi > IP address.'
    $script:tv = (Read-Host 'TV address (for example 192.168.1.50)').Trim()
    if (-not $script:tv) { throw 'No TV address given.' }
    $target = "$($script:tv):5555"
    Invoke-Adb @('disconnect', $target) | Out-Null
    $r = Invoke-Adb @('connect', $target)
    Say $r
    Say ''
    Say 'Look at the TV now: if it asks "Allow USB debugging?", tick "Always allow" and choose OK.' Yellow
    for ($i = 0; $i -lt 30; $i++) {
        $state = Invoke-Adb @('-s', $target, 'get-state')
        if ($state -eq 'device') { Say "Connected to the TV at $($script:tv)." Green; return }
        Start-Sleep -Seconds 2
    }
    throw "Could not connect to $target. Check the address, that USB debugging is on, and that you allowed this PC on the TV."
}

function Tv([string[]]$a) { return Invoke-Adb (@('-s', "$($script:tv):5555") + $a) }

function Install-App($app) {
    $apk = Join-Path $kit $app.apk
    if (-not (Test-Path $apk)) { throw "$($app.apk) is not in the kit folder ($kit)." }
    Say "Installing $($app.name)..." Cyan
    $r = Tv @('install', '-r', $apk)
    if ($r -notmatch 'Success') { throw "Install failed: $r" }
    Say "$($app.name) installed." Green
}

function Set-Home($app, [bool]$stick) {
    $r = Tv @('shell', 'cmd', 'package', 'set-home-activity', $app.pkg)
    Say "Home screen set to $($app.name). $r"
    if ($stick) {
        $installed = Tv @('shell', 'pm', 'list', 'packages')
        foreach ($p in $stockLaunchers) {
            if ($installed -match "package:$([regex]::Escape($p))(\r?\n|$)") {
                Tv @('shell', 'pm', 'disable-user', '--user', '0', $p) | Out-Null
                Say "  switched off the TV's own home screen: $p"
            }
        }
    }
    Tv @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
    Say 'Press the Home button on the TV remote: Chill TV should open.' Green
    if (-not $stick) { Say 'If the TV''s old home screen still opens, run this again and choose "make it stick".' }
}

function Undo-All {
    foreach ($p in $stockLaunchers) {
        $r = Tv @('shell', 'pm', 'enable', $p)
        if ($r -match 'new state: enabled') { Say "  switched back on: $p" }
    }
    $first = $stockLaunchers | Where-Object { (Tv @('shell', 'pm', 'path', $_)) -match 'package:' } | Select-Object -First 1
    if ($first) { Tv @('shell', 'cmd', 'package', 'set-home-activity', $first) | Out-Null; Say "Home screen set back to $first." Green }
    Say 'Chill TV stays installed; uninstall it in Settings > Apps if you want.'
}

try {
    $host.UI.RawUI.WindowTitle = 'Chill TV Helper'
    Say 'Chill TV Helper' White
    Say '==============='
    Get-Adb
    Connect-Tv
    $quit = $false
    while (-not $quit) {
        Say ''
        Say 'What would you like to do?' White
        Say '  1  Install Chill TV Home and make it the home screen (family TV)'
        Say '  2  Install Chill TV Showcase and make it the home screen (shop, clinic or office TV)'
        Say '  3  Make it stick: also switch off the TV''s own home screen (if Home still opens the old one)'
        Say '  4  Undo: put the TV''s own home screen back'
        Say '  5  Quit'
        switch ((Read-Host 'Type 1-5 and press Enter').Trim()) {
            '1' { Install-App $apps.home; Set-Home $apps.home $false; Pause-Key }
            '2' { Install-App $apps.showcase; Set-Home $apps.showcase $false; Pause-Key }
            '3' {
                $which = (Read-Host 'Which one is your home screen? Type H for Home or S for Showcase').Trim().ToUpper()
                $app = if ($which -eq 'S') { $apps.showcase } else { $apps.home }
                Set-Home $app $true; Pause-Key
            }
            '4' { Undo-All; Pause-Key }
            '5' { $quit = $true }
            default { Say 'Please type a number from 1 to 5.' Yellow }
        }
    }
    Invoke-Adb @('disconnect', "$($script:tv):5555") | Out-Null
    Say 'Done. You can turn "USB debugging" off again in the TV''s Developer options.' Green
} catch {
    Say ''
    Say "Something went wrong: $($_.Exception.Message)" Red
    Pause-Key
}
