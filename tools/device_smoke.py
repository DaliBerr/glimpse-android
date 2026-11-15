"""ADB smoke checks for this app on a disposable emulator with generated fixtures only.

Usage: python tools/device_smoke.py inspect|capture NAME|tap TEXT|swipe DIRECTION|start
"""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "AppData/Local/Android/Sdk"))) / "platform-tools/adb.exe"
SERIAL = "emulator-5554"
PACKAGE = "com.albumclean.app"

def adb(*args: str) -> str:
    return subprocess.check_output([str(ADB), "-s", SERIAL, *args], timeout=30).decode("utf-8", errors="replace")

def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/photoclean-ui.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/photoclean-ui.xml"))

def entries():
    return [{k: n.get(k, "") for k in ("text", "content-desc", "resource-id", "clickable", "bounds")} for n in tree().iter("node") if n.get("text") or n.get("content-desc")]

def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))

def tap(text):
    nodes = list(tree().iter("node"))
    matches = [n for n in nodes if n.get("content-desc") == text or n.get("resource-id") == text]
    if not matches:
        matches = [n for n in nodes if n.get("text") == text]
    if not matches:
        matches = [n for n in nodes if text in n.get("content-desc", "")]
    if not matches:
        raise RuntimeError(f"Control not found: {text}; state={entries()}")
    n = next((n for n in matches if n.get("clickable") == "true"), matches[0])
    x1, y1, x2, y2 = bounds(n)
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

def capture(name):
    output = ROOT / "artifacts" / f"{name}.png"
    output.parent.mkdir(exist_ok=True)
    output.write_bytes(subprocess.check_output([str(ADB), "-s", SERIAL, "exec-out", "screencap", "-p"], timeout=15))
    print(str(output))

def swipe(direction):
    card = next(n for n in tree().iter("node") if "照片卡片" in n.get("content-desc", ""))
    x1, y1, x2, y2 = bounds(card)
    x, y = (x1+x2)//2, (y1+y2)//2
    dx, dy = {"left": (-300, 0), "right": (300, 0), "up": (0, -220), "down": (0, 220)}[direction]
    adb("shell", "input", "swipe", str(x), str(y), str(x+dx), str(y+dy), "400")

if __name__ == "__main__":
    command = sys.argv[1]
    if command == "inspect": print(json.dumps(entries(), ensure_ascii=False, indent=2))
    elif command == "capture": capture(sys.argv[2])
    elif command == "tap": tap(sys.argv[2])
    elif command == "swipe": swipe(sys.argv[2])
    elif command == "start": print(adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity"))
    else: raise ValueError(command)
