"""Exercise the signed APK on emulator-5554. Delete only one generated test image."""
import json
import time
from pathlib import Path
from device_smoke import adb, tree, entries, tap, capture, swipe, bounds, PACKAGE, ROOT

results = []

def record(name):
    results.append(name)
    print("PASS " + name, flush=True)
    (ROOT / "artifacts/release-smoke.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

def settle(): time.sleep(.6)

def card():
    return next(n for n in tree().iter("node") if "照片卡片" in n.get("content-desc", "")).get("content-desc")

def count():
    output = adb("shell", "content", "query", "--uri", "content://media/external/images/media", "--projection", "_id")
    return sum(1 for line in output.splitlines() if line.startswith("Row:"))

def press_system(positive):
    nodes = list(tree().iter("node"))
    candidates = ("Allow", "Delete", "允许", "删除", "android:id/button1") if positive else ("Don't allow", "Cancel", "不允许", "取消", "android:id/button2")
    for label in candidates:
        if any(n.get("text") == label or n.get("resource-id") == label for n in nodes):
            tap(label)
            return
    raise RuntimeError("System confirmation not found: " + json.dumps(entries(), ensure_ascii=False))

adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
settle()
assert any("给相册" in n["text"] for n in entries())
capture("welcome")
record("首次启动与权限引导")
adb("shell", "pm", "grant", PACKAGE, "android.permission.READ_MEDIA_IMAGES")
adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
settle()
record("照片授权后读取真实 MediaStore")

# Select a generated image for representative screenshots and the destructive check.
for _ in range(80):
    if "PhotoClean_Sample_" in card(): break
    tap("保留"); settle()
else: raise RuntimeError("No test fixture reached; refusing the deletion check")
capture("light")
for direction in ("left", "right", "up", "down"):
    previous = card()
    swipe(direction); settle()
    assert card() != previous
    tap("撤销"); settle()
    assert card() == previous
    record(f"{direction} 滑动与撤销")

tap("外观与设置"); tap("深色"); adb("shell", "input", "keyevent", "4"); settle()
capture("dark")
record("深色模式")
tap("外观与设置"); tap("浅色"); adb("shell", "input", "keyevent", "4"); settle()
record("浅色模式")

previous = card()
tap("照片卡片"); settle()
capture("detail")
zoom = next(n for n in tree().iter("node") if "双指缩放" in n.get("content-desc", ""))
x1, y1, x2, y2 = bounds(zoom)
x, y = (x1+x2)//2, (y1+y2)//2
adb("shell", f"input tap {x} {y}; sleep 0.08; input tap {x} {y}")
settle()
assert any(n["text"] == "3.0×" for n in entries())
capture("zoomed")
record("详情双击放大至 3 倍")
tap("分享原图"); settle()
assert "ChooserActivity" in adb("shell", "dumpsys", "activity", "activities")
capture("share")
record("原图系统分享面板")
adb("shell", "input", "keyevent", "4"); settle()
tap("关闭详情"); settle()
assert card() == previous
record("查看与分享不改变整理选择")

assert "PhotoClean_Sample_" in card(), "Refusing to delete a non-fixture photo"
before_count = count()
tap("待删除"); settle()
tap("检查清单  ›"); settle()
capture("pending")
tap("确认删除 1 张照片"); tap("再检查一下"); settle()
assert count() == before_count
record("取消应用确认不删除原图")
tap("确认删除 1 张照片"); tap("继续删除"); settle()
capture("system-delete")
press_system(False); settle()
assert count() == before_count
record("取消系统确认保留原图与清单")
tap("确认删除 1 张照片"); tap("继续删除"); settle()
press_system(True); settle()
assert count() == before_count - 1
assert any(n["text"] == "0 张待删除" for n in entries())
record("确认系统删除后原图移除且清单清空")
tap("返回整理"); settle()
before = card()
tap("保留"); settle()
adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
settle()
assert card() != before
record("进程重启后保留记录仍生效")
adb("shell", "pm", "revoke", PACKAGE, "android.permission.READ_MEDIA_IMAGES")
adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
settle()
assert any("给相册" in n["text"] for n in entries())
record("撤销照片权限后安全返回授权页")
adb("shell", "pm", "grant", PACKAGE, "android.permission.READ_MEDIA_IMAGES")
adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
record("发布包冒烟检查完成")
