"""Run device tests using a locally built, signed release as the updater fixture."""
import os
from pathlib import Path
import subprocess
import re
import base64
import sys

root = Path(__file__).resolve().parents[1]
default_sdk = Path.home() / ("AppData/Local/Android/Sdk" if os.name == "nt" else "Android/Sdk")
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or default_sdk)
adb = str(sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
serial = sys.argv[1] if len(sys.argv) > 1 else "emulator-5554"
command = [adb, "-s", serial]
for apk in [root / "app/build/outputs/apk/debug/app-debug.apk", root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]:
    subprocess.run(command + ["install", "-r", str(apk)], check=True)
subprocess.run(command + ["shell", "-T", "run-as com.albumclean.app.debug sh -c 'base64 -d > cache/update-fixture.apk'"],
    input=base64.encodebytes((root / "app/build/outputs/apk/release/app-release.apk").read_bytes()), check=True)
prior = subprocess.check_output(command + ["shell", "appops", "get", "com.albumclean.app.debug", "REQUEST_INSTALL_PACKAGES"], text=True)
match = re.search(r"REQUEST_INSTALL_PACKAGES: (\w+)", prior)
prior_mode = match.group(1) if match else "default"
subprocess.run(command + ["shell", "appops", "set", "com.albumclean.app.debug", "REQUEST_INSTALL_PACKAGES", "allow"], check=True)
try:
    completed = subprocess.run(command + ["shell", "am", "instrument", "-w", "-e", "releaseApk",
        "/data/user/0/com.albumclean.app.debug/cache/update-fixture.apk",
        "com.albumclean.app.debug.test/androidx.test.runner.AndroidJUnitRunner"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
finally:
    subprocess.run(command + ["shell", "appops", "set", "com.albumclean.app.debug", "REQUEST_INSTALL_PACKAGES", prior_mode], check=True)
output = completed.stdout.decode("utf-8", errors="replace")
print(output)
if completed.returncode or "FAILURES!!!" in output or re.search(r"OK \([1-9][0-9]* tests?\)", output) is None:
    raise SystemExit("Device tests did not finish successfully")
