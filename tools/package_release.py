"""Package the local release and portable sources, excluding local signing material."""
from pathlib import Path
import hashlib
import json
import shutil
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
out = root / "artifacts"
out.mkdir(exist_ok=True)
configuration = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
version = re.search(r'versionName = "([0-9]+\.[0-9]+\.[0-9]+)"', configuration).group(1)
build = int(re.search(r'versionCode = ([0-9]+)', configuration).group(1))
commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
if subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip():
    raise RuntimeError("Commit the reviewed source before packaging a release")
apk = out / f"Glimpse-{version}.apk"
shutil.copy2(root / "app/build/outputs/apk/release/app-release.apk", apk)
metadata = {
    "schema": 1, "packageName": "com.albumclean.app", "versionName": version, "versionCode": build,
    "apkName": apk.name, "size": apk.stat().st_size,
    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "sourceCommit": commit,
}
manifest = out / "android-update.json"
manifest.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
source = out / f"Glimpse-{version}-source.zip"
subprocess.run(["git", "archive", "--format=zip", "--prefix=glimpse-android/", "-o", str(source), "HEAD"], cwd=root, check=True)
hashes = {file.name: {"bytes": file.stat().st_size, "sha256": hashlib.sha256(file.read_bytes()).hexdigest()} for file in (apk, source, manifest)}
(out / "SHA256.json").write_text(json.dumps(hashes, ensure_ascii=False, indent=2), encoding="utf-8")
(out / "SHA256SUMS.txt").write_text("".join(f"{item['sha256']}  {name}\n" for name, item in hashes.items()), encoding="utf-8")
with zipfile.ZipFile(source) as archive:
    assert archive.testzip() is None
    assert not any(".signing/" in name or "local.properties" in name for name in archive.namelist())
print(json.dumps(hashes, ensure_ascii=False, indent=2))
