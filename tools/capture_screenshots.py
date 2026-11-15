"""Capture README assets on the disposable emulator, using generated photos only."""
from pathlib import Path
import shutil
import time
from device_smoke import adb, entries, tap, capture, tree, PACKAGE, ROOT

def settle():
    time.sleep(.5)
    tree()

def screenshot(name):
    settle(); capture(name)
    shutil.copy2(ROOT / "artifacts" / f"{name}.png", ROOT / "docs/screenshots" / f"{name}.png")

def theme(name):
    tap("外观与设置"); tap(name)
    adb("shell", "input", "keyevent", "4"); settle()

adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
settle()
theme("浅色")
for _ in range(100):
    photos = [node["content-desc"] for node in entries() if "照片卡片" in node["content-desc"]]
    if photos and "PhotoClean_Sample_" in photos[0]: break
    tap("保留"); settle()
else: raise RuntimeError("No generated image found; refusing to capture private photos")
screenshot("light")
theme("深色"); screenshot("dark")
tap("照片卡片"); screenshot("detail"); tap("关闭详情")
theme("浅色")
tap("待删除"); settle(); tap("检查清单  ›"); screenshot("pending")
tap("恢复"); tap("返回整理")
tap("外观与设置")
for _ in range(3):
    if any(n["text"] == "隐私说明" for n in entries()): break
    adb("shell", "input", "swipe", "360", "1050", "360", "350", "450")
screenshot("settings")
tap("隐私说明"); settle()
assert any("IP 地址" in n["text"] for n in entries())
tap("知道了"); adb("shell", "input", "keyevent", "4")
print("README screenshots and privacy entry verified")
