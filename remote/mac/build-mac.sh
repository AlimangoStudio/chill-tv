#!/bin/bash
# Builds "Chill TV Remote.app" into ~/Applications and starts it. macOS 12 (Monterey) or newer.
# Needs only the Xcode Command Line Tools (xcode-select --install). Run: bash build-mac.sh
# Computer mode also needs scrcpy (brew install scrcpy); real key presses and typing need adb.
# A renamed install: NAME="Pilar TV Remote" BUNDLE_ID=ph.pilar.tvremote APP=/path/to/X.app NO_OPEN=1 bash build-mac.sh
set -euo pipefail
cd "$(dirname "$0")"
NAME="${NAME:-Chill TV Remote}"
BUNDLE_ID="${BUNDLE_ID:-app.alimango.chilltv.remote}"
APP="${APP:-$HOME/Applications/$NAME.app}"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
swiftc -O -parse-as-library -target "$(uname -m)-apple-macos12" ChillRemote.swift -o "$APP/Contents/MacOS/ChillRemote"
cp tv-computer-mode.sh "$APP/Contents/Resources/"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>$NAME</string>
  <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
  <key>CFBundleExecutable</key><string>ChillRemote</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>LSMinimumSystemVersion</key><string>12.0</string>
  <key>LSUIElement</key><true/>
  <!-- Voice: the Mac asks once for the microphone and speech recognition. -->
  <key>NSMicrophoneUsageDescription</key><string>Voice listens for "TV" and a command, such as "TV, open Netflix".</string>
  <key>NSSpeechRecognitionUsageDescription</key><string>Voice turns what you say into a remote command. On Macs that support it, this happens on the Mac.</string>
  <!-- The TV's remote server answers plain HTTP on your own network. -->
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict>
  <!-- macOS 15+: asks once to reach devices on the local network (the TV, and finding it after its address changes). -->
  <key>NSLocalNetworkUsageDescription</key><string>Chill TV Remote talks to your TV on your home network and finds it again when its address changes.</string>
</dict></plist>
PLIST
codesign --force --sign - "$APP"
echo "Built: $APP"
[ "${NO_OPEN:-}" = 1 ] || open "$APP"
