#!/usr/bin/env bash
# Installs the launcher on an Android TV / Google TV over Wi-Fi ADB and makes it the Home screen.
#   ./gradlew assembleRelease
#   scripts/install.sh 192.168.1.50
# Needs adb (macOS: brew install --cask android-platform-tools) and "USB/ADB debugging" turned on in the TV's
# Developer options. Accept the "Allow debugging?" prompt on the TV the first time.
set -euo pipefail

TV="${1:-}"
[[ -n "$TV" ]] || { echo "usage: $0 <tv-ip>[:port]" >&2; exit 1; }
[[ "$TV" == *:* ]] || TV="$TV:5555"

APK="$(cd "$(dirname "$0")/.." && pwd)/app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || { echo "build the APK first: ./gradlew assembleRelease" >&2; exit 1; }

PKG=dev.dhruv.tvhome
adb connect "$TV" >/dev/null
adb -s "$TV" get-state >/dev/null || { echo "TV not reachable or not authorized" >&2; exit 1; }

adb -s "$TV" install -r "$APK"
# The Wi-Fi network name on the top bar needs the location permission, which a TV cannot ask for on screen.
adb -s "$TV" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION || true
adb -s "$TV" shell cmd package set-home-activity "$PKG/.MainActivity"
adb -s "$TV" shell input keyevent KEYCODE_HOME
echo "Installed. If Home still opens the old launcher, see 'Making it the Home screen' in the README."
