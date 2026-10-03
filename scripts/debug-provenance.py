"""Bind device-test APKs to the exact checked-out GitHub commit before running them."""
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

if len(sys.argv) == 1:
    actual_source = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if actual_source != os.environ["GITHUB_SHA"]:
        raise SystemExit("The build checkout differs from the workflow source SHA")
    root = Path("app/build/outputs/apk")
    files = [root / "debug/app-debug.apk", root / "androidTest/debug/app-debug-androidTest.apk"]
    result = {
        "sourceSha": actual_source,
        "runUrl": f'{os.environ["GITHUB_SERVER_URL"]}/{os.environ["GITHUB_REPOSITORY"]}/actions/runs/{os.environ["GITHUB_RUN_ID"]}',
        "apks": [{"filename": file.name, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()} for file in files],
        "adMode": "debug demo-banner-yandex only",
    }
    (root / "debug-provenance.json").write_text(json.dumps(result, indent=2) + "\n")
else:
    root = Path(sys.argv[1])
    proofs = list(root.rglob("debug-provenance.json"))
    if len(proofs) != 1:
        raise SystemExit("One authoritative debug provenance file is required")
    result = json.loads(proofs[0].read_text())
    if result["sourceSha"] != os.environ["GITHUB_SHA"]:
        raise SystemExit("Device tests would use APKs from another source commit")
    for entry in result["apks"]:
        files = list(root.rglob(entry["filename"]))
        if len(files) != 1 or hashlib.sha256(files[0].read_bytes()).hexdigest() != entry["sha256"]:
            raise SystemExit(f'APK mismatch: {entry["filename"]}')
    Path("ci-artifacts/debug-provenance.json").write_text(json.dumps(result, indent=2) + "\n")
print("Exact source commit and both device APK hashes confirmed.")
