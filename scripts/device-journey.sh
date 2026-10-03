#!/usr/bin/env bash
set -euo pipefail
mkdir -p ci-artifacts
python3 scripts/debug-provenance.py ci-apks
adb wait-for-device
device_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
font_scale_changed=false

collect_device_proof() {
    local status=$?
    trap - EXIT
    set +e
    # Collect evidence before emulator-runner tears down the device, including failed tests.
    if [ "$status" -ne 0 ]; then
        timeout 10s adb shell dumpsys power > ci-artifacts/failure-power.txt
        timeout 10s adb shell dumpsys window > ci-artifacts/failure-window.txt
        timeout 10s adb shell dumpsys activity top > ci-artifacts/failure-activity.txt
        timeout 10s adb exec-out screencap -p > ci-artifacts/failure-screen.png
        timeout 15s adb pull /sdcard/Android/data/com.jonkryl.sumpath/files/screenshots ci-artifacts/screenshots
        timeout 10s adb shell dumpsys package com.jonkryl.sumpath > ci-artifacts/package.txt
        timeout 10s adb logcat -d -v threadtime > ci-artifacts/logcat.txt
    fi
    if [ "$font_scale_changed" = true ]; then
        timeout 10s adb shell settings put system font_scale 1.0 >/dev/null 2>&1
    fi
    exit "$status"
}
trap collect_device_proof EXIT

prepare_display() {
    # Package installation can outlast API 24's default screen timeout after emulator boot.
    adb shell settings put system screen_off_timeout 1800000
    adb shell svc power stayon true
    adb shell input keyevent 224 # KEYCODE_WAKEUP never toggles an already awake screen off.
    if [ "$device_api" -ge 26 ]; then
        adb shell wm dismiss-keyguard || adb shell input keyevent 82
    else
        adb shell input keyevent 82 # Dismiss the API 24 emulator's unsecured keyguard.
    fi
}
prepare_display
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r "$(find ci-apks -name 'app-debug.apk' -print -quit)"
adb install -r "$(find ci-apks -name 'app-debug-androidTest.apk' -print -quit)"
adb shell pm clear com.jonkryl.sumpath

run_instrumentation() {
    local report="$1"
    shift
    prepare_display
    adb shell dumpsys power > "${report%.txt}-power-before.txt"
    adb shell am instrument -w -r "$@" com.jonkryl.sumpath.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
    python3 scripts/check-instrumentation.py "$report"
}

# Repository persistence tests use an isolated directory. The journey saves a real puzzle in the app's files.
run_instrumentation ci-artifacts/01-puzzle-journey.txt -e notClass com.jonkryl.sumpath.RestartPersistenceTest,com.jonkryl.sumpath.LargeFontAccessibilityTest
adb shell am force-stop com.jonkryl.sumpath
# A second instrumentation invocation creates a fresh application process and reads the saved puzzle.
run_instrumentation ci-artifacts/02-process-restart.txt -e class com.jonkryl.sumpath.RestartPersistenceTest
if [ "$device_api" -ge 24 ]; then
    # Use the actual Android setting; resources overrides can disappear on recreation.
    font_scale_changed=true
    adb shell settings put system font_scale 2.0
    adb shell am force-stop com.jonkryl.sumpath
    run_instrumentation ci-artifacts/03-large-font.txt -e class com.jonkryl.sumpath.LargeFontAccessibilityTest
    adb shell settings put system font_scale 1.0
    font_scale_changed=false
fi
adb pull /sdcard/Android/data/com.jonkryl.sumpath/files/screenshots ci-artifacts/screenshots
adb shell dumpsys package com.jonkryl.sumpath > ci-artifacts/package.txt
adb logcat -d -v threadtime > ci-artifacts/logcat.txt
