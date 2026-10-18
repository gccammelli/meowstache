#!/usr/bin/env python3
"""Bounded Appium UI checks against only the isolated Meowstache test app."""
import base64
import json
import os
from pathlib import Path
import signal
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from install import TEST_APP, WDA

ELEMENT_KEY = "element-6066-11e4-a52e-4f735466cecf"
LOCAL_DEMO = ("Demo mode: made-up robots on the ESPHome firmware, through a pretend "
              "Home Assistant. Log out to connect yours.")


class Driver:
    def __init__(self):
        self.session = None

    def request(self, method, route, data=None):
        prefix = f"/session/{self.session}" if self.session else ""
        request = Request("http://127.0.0.1:4723" + prefix + route,
                          data=json.dumps(data).encode() if data is not None else None,
                          headers={"Content-Type": "application/json"}, method=method)
        try:
            with urlopen(request, timeout=180) as response:
                value = json.load(response)["value"]
        except HTTPError as exc:
            # Do not leak page source, device identifiers or signing data in exception messages.
            raise RuntimeError(f"Appium command failed: HTTP {exc.code}") from None
        if isinstance(value, dict) and value.get("error"):
            raise RuntimeError("Appium returned a WebDriver error")
        return value

    def execute(self, script, args):
        return self.request("POST", "/execute/sync", {"script": script, "args": [args]})

    def foreground(self):
        if self.execute("mobile: activeAppInfo", {}).get("bundleId") != TEST_APP:
            raise RuntimeError("Test app is not foreground; refusing UI access/capture")

    def find(self, label, timeout=25, scroll=False):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.foreground()
            elements = self.request("POST", "/elements", {"using": "accessibility id", "value": label})
            for element in reversed(elements):
                identifier = element[ELEMENT_KEY]
                if self.request("GET", f"/element/{identifier}/displayed"):
                    return identifier
            if scroll:
                self.foreground()
                self.execute("mobile: swipe", {"direction": "up"})
            time.sleep(0.5)
        raise AssertionError(f"UI did not show {label!r}")

    def tap(self, label, scroll=False):
        identifier = self.find(label, scroll=scroll)
        self.foreground()
        self.request("POST", f"/element/{identifier}/click", {})

    def capture(self, directory, label):
        self.foreground()
        screenshot = self.request("GET", "/screenshot")
        source = self.request("GET", "/source")
        self.foreground()
        (directory / f"{label}.png").write_bytes(base64.b64decode(screenshot, validate=True))
        (directory / f"{label}.xml").write_text(source)


def exercise(driver, directory):
    # Relaunch only the test app; no reset, uninstall, alert acceptance or production app access.
    driver.execute("mobile: terminateApp", {"bundleId": TEST_APP})
    driver.execute("mobile: launchApp", {"bundleId": TEST_APP})
    try:
        driver.find("Robbie", timeout=3)
    except AssertionError:
        driver.tap("Local: Home Assistant")
        driver.tap("I understand", scroll=True)
        driver.tap("Try the demo")
    driver.find(LOCAL_DEMO)
    driver.find("Robbie")
    driver.capture(directory, "home")
    driver.tap("Devices")
    driver.find("Bobbie")
    driver.tap("Robbie")
    driver.tap("Controls")
    driver.tap("Lights")
    driver.tap("Off")
    driver.tap("Set")
    driver.tap("Lights")
    off = driver.find("Off")
    selected = driver.request("GET", f"/element/{off}/attribute/selected")
    if str(selected).lower() not in ("true", "1"):
        raise AssertionError("Night-light command did not read back as Off")
    driver.tap("Cancel")
    driver.capture(directory, "controls")
    driver.tap("History")
    driver.capture(directory, "history")
    driver.tap("Back")
    driver.tap("Pets")
    driver.find("Mochi")
    driver.find("Luna")
    driver.capture(directory, "pets")


async def device_version():
    from pymobiledevice3.lockdown import create_using_usbmux
    from pymobiledevice3.usbmux import list_devices
    devices = [device for device in await list_devices() if device.is_usb]
    if len(devices) != 1:
        raise RuntimeError("Connect exactly one USB test iPhone")
    client = await create_using_usbmux(serial=devices[0].serial, autopair=False, connection_type="USB")
    try:
        if not client.paired:
            raise RuntimeError("Phone is not paired")
        return devices[0].serial, client.all_values["ProductVersion"]
    finally:
        await client.close()


def main():
    import asyncio
    import logging
    logging.disable(logging.CRITICAL)
    os.umask(0o077)
    directory = Path(__file__).resolve().parents[2] / "build/ios-smoke" / time.strftime("%Y%m%d-%H%M%S")
    directory.mkdir(parents=True, mode=0o700)
    udid, version = asyncio.run(device_version())
    driver = Driver()
    try:
        value = driver.request("POST", "/session", {"capabilities": {"alwaysMatch": {
            "platformName": "iOS", "appium:automationName": "xcuitest",
            "appium:udid": udid, "appium:platformVersion": version,
            "appium:bundleId": TEST_APP, "appium:noReset": True, "appium:fullReset": False,
            "appium:autoLaunch": False, "appium:usePreinstalledWDA": True,
            "appium:updatedWDABundleId": WDA, "appium:updatedWDABundleIdSuffix": "",
            "appium:newCommandTimeout": 60, "appium:wdaLaunchTimeout": 90000,
        }}})
        driver.session = value["sessionId"]
        exercise(driver, directory)
        (directory / "result.json").write_text(json.dumps({"passed": True, "mode": "local-demo"}))
        print("iPhone local-demo UI smoke passed; private evidence saved under build/ios-smoke/")
    finally:
        if driver.session:
            driver.request("DELETE", "")


if __name__ == "__main__":
    def deadline_expired(_signum, _frame):
        raise TimeoutError("iPhone smoke exceeded its 15-minute deadline")
    signal.signal(signal.SIGALRM, deadline_expired)
    signal.alarm(900)
    try:
        main()
    except Exception as exc:
        raise SystemExit(f"iPhone smoke failed ({type(exc).__name__}); not a successful test run") from None
    finally:
        signal.alarm(0)
