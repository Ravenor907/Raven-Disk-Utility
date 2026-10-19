#!/usr/bin/env bash
# Runs inside the emulator job: installs the debug APK, opens each screen on simulated drives,
# screenshots it, and fails if the app crashed. The "run" demo really executes a plan on a
# simulated drive, so the progress and result screens are the real thing.
set -u
PKG=io.github.ravenor907.ravendisk
mkdir -p screens
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
shot() { # name demo wait
  adb shell am force-stop $PKG
  if [ -n "$2" ]; then adb shell am start -W -n $PKG/.MainActivity --es demo "$2" >/dev/null
  else adb shell am start -W -n $PKG/.MainActivity >/dev/null; fi
  sleep "$3"
  adb exec-out screencap -p > "screens/$1.png"
  if [ -z "$(adb shell pidof $PKG | tr -d '\r')" ]; then
    echo "::error title=App crashed::screen '$1' is not running"
    adb logcat -d -s AndroidRuntime:E | tail -40
    exit 1
  fi
}
shot launch "" 4
shot dashboard dashboard 11
shot device device 11
shot flash flash 11
shot hdd hdd 11
shot create create 11
shot format format 11
shot erase erase 11
shot review review 11
shot review-table review-table 11
shot running run 10
shot done done 15
shot capabilities capabilities 10
shot log log 10
# Scroll the device workspace to the actions and health sections.
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity --es demo hdd >/dev/null
sleep 11
adb shell input swipe 500 1800 500 400 300
sleep 2
adb exec-out screencap -p > screens/hdd-scrolled.png
# Landscape, like a handheld or tablet.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
shot device-landscape device 12
adb shell settings put system user_rotation 0
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  msg=$(adb logcat -d | grep -A15 "FATAL EXCEPTION" | head -30 | sed ':a;N;$!ba;s/\n/%0A/g')
  echo "::error title=Crash in logcat::$msg"
  exit 1
fi
echo "::notice title=Timings::$(adb logcat -d -s RavenDisk:I | grep -E 'refresh|simulated|run' | tail -12 | sed ':a;N;$!ba;s/\n/%0A/g')"
echo "All screens rendered without crashing"
