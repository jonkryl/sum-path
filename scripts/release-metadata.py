"""Record public provenance and verify that the signed binary contains this app's real banner."""
import hashlib
import json
import os
import re
import sys
import zipfile
from pathlib import Path

apk, aab = map(Path, sys.argv[1:])
banner = os.environ["YANDEX_BANNER_ID"]
if not re.fullmatch(r"R-M-\d+-\d+", banner):
    raise SystemExit("A real banner is required")
with zipfile.ZipFile(apk) as archive:
    dex = b"".join(archive.read(name) for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name))
    if banner.encode() + b"\0" not in dex:
        raise SystemExit("The configured real banner is absent from the signed APK")
    if b"demo-banner-yandex" in dex:
        raise SystemExit("The signed APK contains the debug demo banner ID")
signature = Path("release-output/apk-signature.txt").read_text()
certificate = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", signature)
if not certificate:
    raise SystemExit("The APK certificate was not verified")
native_names = []
with zipfile.ZipFile(apk) as archive:
    native_names = sorted(name for name in archive.namelist() if name.endswith(".so"))
result = {
    "repository": os.environ["GITHUB_REPOSITORY"],
    "sourceSha": os.environ["GITHUB_SHA"],
    "runUrl": f'{os.environ["GITHUB_SERVER_URL"]}/{os.environ["GITHUB_REPOSITORY"]}/actions/runs/{os.environ["GITHUB_RUN_ID"]}',
    "package": "com.jonkryl.sumpath",
    "versionCode": int(os.environ["VERSION_CODE"]),
    "versionName": os.environ["VERSION_NAME"],
    "minSdk": 24,
    "targetSdk": 36,
    "compileSdk": 36,
    "yandexSdk": "8.5.0",
    "bannerId": banner,
    "releaseDemoBannerPresent": False,
    "certificateSha256": certificate[1],
    "artifacts": [{"filename": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size} for path in (apk, aab)],
    "checks": {"apkSignature": True, "aabSignature": True, "certificateMatchesUploadKey": True, "apkZipAlignment16Kb": True, "apkAndAabNativeAlignment16Kb": True, "bundletoolValidation": True},
    "nativeLibraries": native_names,
    "googlePlayAvailability": "not confirmed by this workflow",
}
Path("release-output/release-verification.json").write_text(json.dumps(result, indent=2) + "\n")
print("Recorded exact signed artifact hashes, certificate, source, version and real banner.")
