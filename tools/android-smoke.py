#!/usr/bin/env python3
"""Exercise an APK through Android's accessibility tree, with no app test hooks."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "io.github.gccammelli.meowstache"


def run(*args, binary=False, check=True):
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=not binary, check=check, timeout=45).stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--production-on-emulator", action="store_true")
    args = parser.parse_args()
    serial = os.environ.get("ANDROID_SERIAL")
    if not serial:
        devices = re.findall(r"^(\S+)\s+device$", run("adb", "devices"), re.M)
        if len(devices) != 1:
            raise RuntimeError("Set ANDROID_SERIAL when more than one device is connected")
        serial = devices[0]
    adb = ["adb", "-s", serial]
    package = PACKAGE + ".testbed"
    if args.production_on_emulator:
        if run(*adb, "shell", "getprop", "ro.kernel.qemu").strip() != "1":
            raise RuntimeError("Production APK tests may only clear data on an emulator")
        package = PACKAGE
    # Resolve the package from the APK before installing or clearing any data.
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
    aapt_tools = sorted((sdk / "build-tools").glob("*/aapt"))
    aapt = str(aapt_tools[-1]) if aapt_tools else "aapt"
    badging = run(aapt, "dump", "badging", str(args.apk))
    match = re.search(r"package: name='([^']+)'", badging)
    if not match or match[1] != package:
        raise RuntimeError(f"Expected isolated test package {package}; refusing to install/clear APK")
    label = os.environ.get("ANDROID_TEST_LABEL", "device")
    output = Path("build/android-smoke") / re.sub(r"[^A-Za-z0-9._-]", "_", label)
    output.mkdir(parents=True, exist_ok=True)
    run(*adb, "install", "-r", str(args.apk))
    run(*adb, "shell", "am", "force-stop", package)
    run(*adb, "shell", "pm", "clear", package)
    run(*adb, "shell", "pm", "grant", package, "android.permission.POST_NOTIFICATIONS", check=False)
    run(*adb, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run(*adb, "shell", "wm", "dismiss-keyguard", check=False)
    run(*adb, "shell", "am", "start", "-W", "-n", f"{package}/{PACKAGE}.MainActivity")

    def tree():
        run(*adb, "shell", "uiautomator", "dump", "/sdcard/meowstache-smoke.xml", check=False)
        text = run(*adb, "exec-out", "cat", "/sdcard/meowstache-smoke.xml", check=False)
        (output / "screen.xml").write_text(text)
        try:
            return ET.fromstring(text)
        except ET.ParseError:
            return ET.Element("empty")

    def find(label, scroll=False, attempts=12):
        for attempt in range(attempts):
            root = tree()
            nodes = [n for n in root.iter("node") if n.get("package") == package and
                     (n.get("text") == label or n.get("content-desc") == label)]
            if nodes:
                parents = {child: parent for parent in root.iter() for child in parent}
                node = nodes[-1]
                while node in parents and node.get("clickable") != "true":
                    node = parents[node]
                return node
            if scroll and attempt % 2:
                node = root.find("node")
                bounds = node.get("bounds", "[0,0][1080,1920]") if node is not None else "[0,0][1080,1920]"
                _, _, width, height = map(int, re.findall(r"\d+", bounds))
                run(*adb, "shell", "input", "swipe", str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 3), "300")
            time.sleep(1)
        raise AssertionError(f"Screen did not show {label!r}; see {output}")

    def tap(label, scroll=False):
        node = find(label, scroll)
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        run(*adb, "shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(0.5)

    def tap_until_visible(label, next_label, next_scroll=False):
        # A freshly launched Compose activity can expose its accessibility tree
        # just before it is ready to consume the first synthetic tap.
        for _ in range(3):
            tap(label)
            try:
                return find(next_label, scroll=next_scroll, attempts=4)
            except AssertionError:
                pass
        raise AssertionError(f"Tapping {label!r} did not show {next_label!r}; see {output}")

    def capture(name):
        (output / f"{name}.png").write_bytes(run(*adb, "exec-out", "screencap", "-p", binary=True))

    try:
        tap_until_visible("Local: Home Assistant", "I understand", next_scroll=True)
        tap("I understand", scroll=True)
        tap("Try the demo")
        find("Robbie")
        capture("home")
        tap("Devices")
        find("Bobbie")
        tap("Robbie")
        tap("Controls")
        tap("Lights")
        tap("Off")
        tap("Set")
        # Reopening proves the command was read back through the local-mode backend.
        tap("Lights")
        off = find("Off")
        assert off.get("selected") == "true" or off.get("checked") == "true", "Night-light command did not persist"
        tap("Cancel")
        capture("controls")
        tap("History")
        capture("history")
        run(*adb, "shell", "input", "keyevent", "KEYCODE_BACK")
        tap("Pets")
        find("Mochi")
        find("Luna", scroll=True)
        capture("pets")
        print("Android local-mode UI and command round trip passed")
    finally:
        capture("last-screen")
        pid = run(*adb, "shell", "pidof", package, check=False).strip().split()
        if not pid:
            raise AssertionError("App process exited during Android smoke test")
        (output / "app-log.txt").write_text(run(*adb, "logcat", "-d", f"--pid={pid[0]}", "-t", "500", check=False))


if __name__ == "__main__":
    main()
