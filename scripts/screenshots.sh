#!/usr/bin/env bash
# Takes screenshots of a debug build in an emulator, with sample data.
# Run by .github/workflows/screenshots.yml while an emulator is booted.
set -euo pipefail

PKG=com.rachitratnammudaliar.target
OUT=docs/screenshots
mkdir -p "$OUT"

./gradlew --no-daemon installDebug

# Emulators on CI are slow, so the launcher sometimes shows "isn't responding".
# Hide system error dialogs and give the system time to settle.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put global anr_show_background 0 || true
adb shell am force-stop com.google.android.apps.nexuslauncher || true
sleep 20

# Clean status bar: fixed clock, full battery, no notifications.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null

shot() {
  local screen=$1 name=$2
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  adb shell am start -S -W -n "$PKG/.MainActivity" --ez target.demo true --es target.screen "$screen" >/dev/null
  sleep 6
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  sleep 1
  adb exec-out screencap -p > "$OUT/$name.png"
  echo "Saved $OUT/$name.png"
}

adb shell cmd uimode night no
shot today home
shot progress progress
shot detail:1 wake
shot detail:3 run
shot edit:0 new
shot settings settings

adb shell cmd uimode night yes
sleep 2
shot today home-dark
adb shell cmd uimode night no
