#!/usr/bin/env bash
set -euo pipefail
mkdir -p ci-artifacts
python3 scripts/debug-provenance.py ci-apks
adb wait-for-device
device_api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
font_scale_changed=false
store_size_changed=false
store_locale_changed=false
store_original_size=reset
store_original_locale=
store_capture_in_progress=false

restore_store_configuration() {
    local failed=0
    if [ "$store_size_changed" = true ]; then
        if timeout 10s adb shell wm size "$store_original_size"; then
            store_size_changed=false
        else
            failed=1
        fi
    fi
    if [ "$store_locale_changed" = true ]; then
        if [ -n "$store_original_locale" ]; then
            if timeout 10s adb shell cmd locale set-app-locales com.jonkryl.sumpath --user 0 --locales "$store_original_locale"; then
                store_locale_changed=false
            else
                failed=1
            fi
        else
            if timeout 10s adb shell cmd locale set-app-locales com.jonkryl.sumpath --user 0; then
                store_locale_changed=false
            else
                failed=1
            fi
        fi
    fi
    return "$failed"
}

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
    restore_store_configuration
    if [ "$store_capture_in_progress" = true ]; then
        timeout 10s adb shell wm size > ci-artifacts/store-screenshots/restored-wm-size.txt
        timeout 10s adb shell cmd locale get-app-locales com.jonkryl.sumpath --user 0 > ci-artifacts/store-screenshots/restored-app-locales.txt
        python3 - "$status" <<'PY'
import json
import os
from pathlib import Path
import sys
directory = Path("ci-artifacts/store-screenshots")
proofs = [json.loads(path.read_text()) for path in directory.glob("proof-*.json")]
restored = all((directory / original).exists() and (directory / restored).exists() and
    (directory / original).read_bytes() == (directory / restored).read_bytes()
    for original, restored in (("original-wm-size.txt", "restored-wm-size.txt"),
        ("original-app-locales.txt", "restored-app-locales.txt")))
(directory / "capture-status.json").write_text(json.dumps({"status": "failed", "exitCode": int(sys.argv[1]),
    "sourceCommitSha": os.environ.get("GITHUB_SHA"), "workflowRunId": os.environ.get("GITHUB_RUN_ID"),
    "configurationRestored": restored, "localeProofs": proofs}, ensure_ascii=False, indent=2) + "\n")
PY
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

capture_store_frames() {
    # Use the existing two-step route, real resources and platform per-app locale support.
    # Keep these viewport changes separate from both original device/accessibility journeys.
    local directory=ci-artifacts/store-screenshots
    mkdir -p "$directory"
    store_capture_in_progress=true
    adb shell wm size > "$directory/original-wm-size.txt"
    store_original_size="$(awk '/Override size:/ {print $3}' "$directory/original-wm-size.txt")"
    if [ -z "$store_original_size" ]; then store_original_size=reset; fi
    adb shell cmd locale get-app-locales com.jonkryl.sumpath --user 0 > "$directory/original-app-locales.txt"
    store_original_locale="$(python3 -c 'import re,sys; text=open(sys.argv[1]).read(); match=re.search(r"are \[([^\]]*)\]", text); assert match, text; print(match.group(1))' "$directory/original-app-locales.txt")"
    store_size_changed=true
    adb shell wm size 1440x2880
    store_locale_changed=true
    for locale in ru-RU en-US; do
        adb shell cmd locale set-app-locales com.jonkryl.sumpath --user 0 --locales "$locale"
        adb shell am force-stop com.jonkryl.sumpath
        prepare_display
        adb shell am start -W -n com.jonkryl.sumpath/.MainActivity > "$directory/launch-$locale.txt"
        python3 - "$locale" "$directory" "$device_api" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

locale, output, api = sys.argv[1:]
output = Path(output)
package = "com.jonkryl.sumpath"
proof = {"locale": locale, "api": int(api), "fontScale": None, "status": "failed"}

def command(*args, timeout=10):
    result = subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    return result.stdout

def shell(*args):
    return command("adb", "shell", *args).decode("utf-8").strip()

def bounds(node):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    assert match, f"Missing screen bounds: {node.attrib}"
    return tuple(map(int, match.groups()))

def visible(node, viewport):
    rectangle = bounds(node)
    left, top, right, bottom = rectangle
    assert 0 <= left < right <= viewport[0] and 0 <= top < bottom <= viewport[1], \
        f"Node outside viewport {viewport}: {node.get('resource-id')} {rectangle}"
    return rectangle

try:
    proof["sourceCommitSha"] = command("git", "rev-parse", "HEAD").decode().strip()
    assert proof["sourceCommitSha"] == os.environ["GITHUB_SHA"], "Capture source differs from workflow source"
    proof["workflowRunId"] = os.environ["GITHUB_RUN_ID"]
    proof["fontScale"] = float(shell("settings", "get", "system", "font_scale"))
    assert proof["fontScale"] == 1.0, f"Store frame font scale is {proof['fontScale']}, expected 1.0"
    wm = shell("wm", "size")
    (output / f"wm-size-{locale}.txt").write_text(wm + "\n")
    sizes = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", wm)
    assert sizes, f"Could not read actual viewport: {wm}"
    viewport = tuple(map(int, sizes[-1]))
    assert viewport == (1440, 2880), f"Actual viewport is {viewport}, expected1440x2880"
    proof["viewportPixels"] = viewport
    locale_report = shell("cmd", "locale", "get-app-locales", package, "--user", "0")
    (output / f"app-locales-{locale}.txt").write_text(locale_report + "\n")
    match = re.search(r"are \[([^\]]*)\]", locale_report)
    assert match and match.group(1) == locale, f"Platform app locale was not applied: {locale_report}"
    proof["actualAppLocale"] = match.group(1)
    resource_path = Path("app/src/main/res") / ("values-ru" if locale == "ru-RU" else "values") / "strings.xml"
    resources = {node.get("name"): "".join(node.itertext()) for node in ET.parse(resource_path).getroot()}
    # re.escape also escapes the dollar signs in Android's positional placeholders.
    def number_label(node, name):
        pattern = re.escape(resources[name])
        for placeholder in ("%1$d", "%2$d"):
            pattern = pattern.replace(re.escape(placeholder), r"\d+")
        text = node.get("text", "")
        assert re.fullmatch(pattern, text), f"Wrong localized {name}: {text!r}"
        visible(node, viewport)
        return text
    last_projection = None
    last_error = "No UI hierarchy returned"
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        try:
            remote_xml = f"/sdcard/sum-path-store-{locale}.xml"
            shell("rm", "-f", remote_xml)
            command("adb", "shell", "uiautomator", "dump", remote_xml, timeout=10)
            xml = command("adb", "shell", "cat", remote_xml)
            (output / f"hierarchy-{locale}.xml").write_bytes(xml)
            root = ET.fromstring(xml)
            app_nodes = [node for node in root.iter("node") if node.get("package") == package]
            by_id = {node.get("resource-id"): node for node in app_nodes if node.get("resource-id")}
            app_name = next(node for node in app_nodes if node.get("text") == resources["app_name"])
            visible(app_name, viewport)
            sum_node = by_id[package + ":id/sum_status"]
            keys_node = by_id[package + ":id/keys_status"]
            board = by_id[package + ":id/board"]
            board_bounds = visible(board, viewport)
            assert abs((board_bounds[2] - board_bounds[0]) - (board_bounds[3] - board_bounds[1])) <= 2, \
                f"The full square board is clipped: {board_bounds}"
            cells = []
            for index in range(25):
                node = by_id[package + f":id/cell_{index}"]
                rectangle = visible(node, viewport)
                left, top, right, bottom = rectangle
                assert board_bounds[0] <= left < right <= board_bounds[2] and board_bounds[1] <= top < bottom <= board_bounds[3], \
                    f"Cell{index} is outside the board: {rectangle}"
                assert abs((right - left) - (bottom - top)) <= 2, f"Cell{index} is clipped: {rectangle}"
                description = node.get("content-desc", "")
                numbers = list(map(int, re.findall(r"\d+", description)))
                assert len(numbers) >= 3 and numbers[:2] == [index // 5 + 1, index % 5 + 1], \
                    f"Cell{index} lacks real row/column/value description: {description!r}"
                assert node.get("clickable") == "true", f"Cell{index} is not a real tap target"
                cells.append({"index": index, "bounds": rectangle, "value": numbers[2], "selected": node.get("selected") == "true"})
            assert len([key for key in by_id if re.fullmatch(re.escape(package) + r":id/cell_\d+", key)]) == 25, "Expected exactly25 cell nodes"
            widths = [cell["bounds"][2] - cell["bounds"][0] for cell in cells]
            heights = [cell["bounds"][3] - cell["bounds"][1] for cell in cells]
            assert max(widths) - min(widths) <= 2 and max(heights) - min(heights) <= 2, \
                f"Partly clipped grid cells: widths={widths}, heights={heights}"
            selected = [cell["index"] for cell in cells if cell["selected"]]
            assert len(selected) == 2, f"Saved partial route changed: selected cells={selected}"
            projection = {"appName": resources["app_name"], "sumLabel": number_label(sum_node, "sum_value"),
                "keysLabel": number_label(keys_node, "keys_value"), "goalBounds": bounds(sum_node),
                "boardBounds": board_bounds, "cells": cells, "selectedRouteCells": selected}
            if projection == last_projection:
                proof["stableGameProjection"] = projection
                break
            last_projection = projection
            last_error = "Game projection has not yet been stable for two consecutive hierarchies"
        except (AssertionError, KeyError, StopIteration, ET.ParseError, subprocess.SubprocessError) as error:
            last_projection = None
            last_error = str(error) or type(error).__name__
        time.sleep(0.5)
    else:
        raise AssertionError(f"Could not capture goal and all25 visible cells in a stable frame: {last_error}")
    screenshot = command("adb", "exec-out", "screencap", "-p")
    assert screenshot[:8] == b"\x89PNG\r\n\x1a\n", "Device did not return a PNG screenshot"
    assert struct.unpack(">II", screenshot[16:24]) == viewport, "Screenshot dimensions differ from checked viewport"
    image_path = output / f"store-{locale}-api-{api}.png"
    image_path.write_bytes(screenshot)
    proof.update(status="passed", screenshot=image_path.name, screenshotSha256=hashlib.sha256(screenshot).hexdigest())
except Exception as error:
    proof["error"] = str(error) or type(error).__name__
    raise
finally:
    (output / f"proof-{locale}.json").write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n")
print(f"Genuine {locale} store frame: visible goal +25 cells, stable two-step route, viewport{viewport}")
PY
    done
    python3 - "$directory" <<'PY'
import json
from pathlib import Path
import sys
directory = Path(sys.argv[1])
proofs = [json.loads((directory / f"proof-{locale}.json").read_text()) for locale in ("ru-RU", "en-US")]
projections = [proof["stableGameProjection"] for proof in proofs]
assert [cell["value"] for cell in projections[0]["cells"]] == [cell["value"] for cell in projections[1]["cells"]], "Locale change altered the real board"
assert projections[0]["selectedRouteCells"] == projections[1]["selectedRouteCells"], "Locale change altered the saved route"
(directory / "capture-comparison.json").write_text(json.dumps({"status": "passed", "sourceCommitSha": proofs[0]["sourceCommitSha"],
    "workflowRunId": proofs[0]["workflowRunId"], "locales": [proof["actualAppLocale"] for proof in proofs],
    "sameBoardAndRoute": True, "viewportPixels": proofs[0]["viewportPixels"]}, indent=2) + "\n")
PY
    restore_store_configuration
    adb shell wm size > "$directory/restored-wm-size.txt"
    adb shell cmd locale get-app-locales com.jonkryl.sumpath --user 0 > "$directory/restored-app-locales.txt"
    cmp "$directory/original-wm-size.txt" "$directory/restored-wm-size.txt"
    cmp "$directory/original-app-locales.txt" "$directory/restored-app-locales.txt"
    python3 - "$directory" <<'PY'
import json
from pathlib import Path
import sys
directory = Path(sys.argv[1])
status = json.loads((directory / "capture-comparison.json").read_text())
status["configurationRestored"] = True
(directory / "capture-status.json").write_text(json.dumps(status, indent=2) + "\n")
PY
    store_capture_in_progress=false
    adb shell am force-stop com.jonkryl.sumpath
}

# Repository persistence tests use an isolated directory. The journey saves a real puzzle in the app's files.
run_instrumentation ci-artifacts/01-puzzle-journey.txt -e notClass com.jonkryl.sumpath.RestartPersistenceTest,com.jonkryl.sumpath.LargeFontAccessibilityTest
adb shell am force-stop com.jonkryl.sumpath
# A second instrumentation invocation creates a fresh application process and reads the saved puzzle.
run_instrumentation ci-artifacts/02-process-restart.txt -e class com.jonkryl.sumpath.RestartPersistenceTest
if [ "$device_api" -eq 36 ]; then
    capture_store_frames
fi
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
