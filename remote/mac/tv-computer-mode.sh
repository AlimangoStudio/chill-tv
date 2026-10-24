#!/bin/sh
# Computer mode for the Chill TV Remote: shows the TV in a window (scrcpy), driven by the Mac keyboard and mouse.
# Usage: tv-computer-mode.sh <tv-address>
# Exit codes the remote understands: 2 = the TV has not allowed this computer yet, 3 = TV not reachable.
HOST="$1"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
command -v scrcpy >/dev/null 2>&1 || { echo "scrcpy not found: brew install scrcpy" >&2; exit 1; }
adb connect "$HOST:5555" >/dev/null 2>&1
STATE=$(adb -s "$HOST:5555" get-state 2>&1)
case "$STATE" in
  device) ;;
  *unauthorized*) exit 2 ;;
  *) exit 3 ;;
esac
# sdk mouse keeps the pointer free to leave the window; uhid keyboard types into any app.
exec scrcpy -s "$HOST:5555" --keyboard=uhid --mouse=sdk --no-audio --max-size 1280 --max-fps 30 --window-title "Chill TV"
