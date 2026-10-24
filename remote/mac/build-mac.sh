#!/bin/bash
# Builds "Chill TV Remote.app" into ~/Applications and starts it. macOS 12 (Monterey) or newer.
# Needs only the Xcode Command Line Tools (xcode-select --install). Run: bash build-mac.sh
# Computer mode also needs scrcpy (brew install scrcpy).
set -euo pipefail
cd "$(dirname "$0")"
APP="$HOME/Applications/Chill TV Remote.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
swiftc -O -parse-as-library -target "$(uname -m)-apple-macos12" ChillRemote.swift -o "$APP/Contents/MacOS/ChillRemote"
cp tv-computer-mode.sh "$APP/Contents/Resources/"
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>Chill TV Remote</string>
  <key>CFBundleIdentifier</key><string>app.alimango.chilltv.remote</string>
  <key>CFBundleExecutable</key><string>ChillRemote</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>LSMinimumSystemVersion</key><string>12.0</string>
  <key>LSUIElement</key><true/>
  <!-- The TV's remote server answers plain HTTP on your own network. -->
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict>
</dict></plist>
PLIST
codesign --force --sign - "$APP"
echo "Built: $APP"
open "$APP"
