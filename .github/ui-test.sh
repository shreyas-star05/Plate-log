#!/usr/bin/env bash
# Runs the UI flow on the emulator in demo mode and screenshots every screen.
set -x
APP=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=com.microfoods.app.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p ui-out
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
sleep 45   # let System UI settle so its "not responding" box doesn't cover screenshots
adb shell settings put global hide_error_dialogs 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS
adb install -r -g "$APP"
adb install -r "$TEST"
run() { adb shell am instrument -w -r -e class "com.microfoods.app.UiFlowTest#$1" "$RUNNER" | tee -a ui-out/instrument.txt; }

run a_logOneMeal
run b_dataSurvivesRestart
run c_manualEntryAndLabelFood

adb pull /sdcard/Android/data/com.microfoods.app/files/shots ui-out/
adb shell run-as com.microfoods.app cat files/microfoods.json > ui-out/microfoods.json || true
adb logcat -d > ui-out/logcat.txt
grep -E "FATAL EXCEPTION" -A12 ui-out/logcat.txt | grep -B2 -A10 "Process: com.microfoods.app" | head -80 > ui-out/crashes.txt
grep -E "ANR in com.microfoods" -A6 ui-out/logcat.txt >> ui-out/crashes.txt
cat ui-out/crashes.txt
if grep -qE "FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|shortMsg=" ui-out/instrument.txt; then echo "UI FLOW FAILED"; exit 1; fi
if [ -s ui-out/crashes.txt ]; then echo "APP CRASHED"; exit 1; fi
echo "UI FLOW PASSED"
