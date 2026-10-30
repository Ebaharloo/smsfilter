#!/usr/bin/env python3
import subprocess, time, os, re, xml.etree.ElementTree as ET

ADB = ["adb"]
DEVICE = os.environ.get("ADB_DEVICE", "192.168.240.112:5555")
OUT = os.environ.get("OUT_DIR", "shots")
os.makedirs(OUT, exist_ok=True)

def run(*args):
    return subprocess.run(ADB + ["-s", DEVICE] + list(args), capture_output=True, text=True)

def tap_bounds(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m: return
    x1,y1,x2,y2 = map(int, m.groups())
    run("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))

def dump():
    run("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    run("pull", "/sdcard/ui.xml", "/tmp/ui.xml")
    return ET.parse("/tmp/ui.xml")

def find_text(root, text):
    for n in root.iter():
        if n.get("text") == text:
            return n.get("bounds")
    return None

def screenshot(name):
    with open(os.path.join(OUT, name), "wb") as f:
        subprocess.run(ADB + ["-s", DEVICE, "exec-out", "screencap", "-p"], stdout=f)
    print("saved", name)

# launch
run("shell", "am", "start", "-n", "ir.smsfilter/.MainActivity")
time.sleep(3)

# open drawer
root = dump()
b = find_text(root, "") or None
# find menu button by content-desc
menu = None
for n in root.iter():
    if n.get("content-desc") == "menu":
        menu = n.get("bounds")
if menu:
    tap_bounds(menu)
    time.sleep(2)

pages = ["Inbox", "Test a message", "Appearance", "Settings", "Features"]
for page in pages:
    # reopen drawer
    root = dump()
    menu = None
    for n in root.iter():
        if n.get("content-desc") == "menu":
            menu = n.get("bounds")
    if menu:
        tap_bounds(menu)
        time.sleep(2)
    root = dump()
    b = find_text(root, page)
    if b:
        tap_bounds(b)
        time.sleep(2)
        screenshot(f"{page.replace(' ', '_').lower()}.png")

# run self-test on Features
root = dump()
b = find_text(root, "Self-test")
if b:
    tap_bounds(b)
    time.sleep(2)
    screenshot("features_selftest.png")
    root = dump()
    for n in root.iter():
        t = n.get("text") or ""
        if "ALL TESTS" in t or "FAIL" in t or "PASSED" in t:
            print(t.replace("&#10;", "\\n"))
print("done")