#!/usr/bin/env python3
"""Install only privately signed Meowstache test bundles; never uninstall apps."""
import argparse
import asyncio
from datetime import datetime, timezone
import logging
import os
from pathlib import Path
import plistlib
import re
import subprocess
import zipfile

TEST_APP = "io.github.gccammelli.meowstache.testbed"
WDA = TEST_APP + ".wda.xctrunner"
PANDY = "io.github.gccammelli.pandy.testbed"
LIBERPET = "io.github.gccammelli.liberpet.testbed"
INSTALL_TARGETS = {"app": TEST_APP, "wda": WDA, "pandy": PANDY, "liberpet": LIBERPET}
INSTALL_TIMEOUT_SECONDS = 300


def validate_profile(info, profile, expected, team, udid):
    if expected not in INSTALL_TARGETS.values():
        raise ValueError("Only an isolated Cammelli test app or its WDA runner may be installed")
    if not re.fullmatch(r"[A-Z0-9]{10}", team):
        raise ValueError("A 10-character Apple Developer Team ID is required")
    if info.get("CFBundleIdentifier") != expected:
        raise ValueError("Archive bundle identifier does not match the isolated test bundle")
    if profile.get("TeamIdentifier") != [team]:
        raise ValueError("Provisioning profile does not match the expected team")
    entitlements = profile.get("Entitlements", {})
    app_id = entitlements.get("application-identifier", "")
    prefix, separator, bundle = app_id.partition(".")
    if not separator or prefix not in profile.get("ApplicationIdentifierPrefix", []) or bundle != expected:
        raise ValueError("Provisioning profile does not explicitly cover this bundle")
    if entitlements.get("com.apple.developer.team-identifier") != team:
        raise ValueError("Profile entitlements do not match the expected team")
    if entitlements.get("get-task-allow") is not True:
        raise ValueError("A development provisioning profile is required")
    if udid not in profile.get("ProvisionedDevices", []):
        raise ValueError("Provisioning profile does not cover the connected phone")
    expiry = profile.get("ExpirationDate")
    if not isinstance(expiry, datetime) or expiry.replace(tzinfo=timezone.utc) <= datetime.now(timezone.utc):
        raise ValueError("Provisioning profile is expired or has no expiration date")


def inspect_ipa(path, expected, team, udid):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names):
            raise ValueError("Duplicate archive entries are not allowed")
        roots = [name[:-len("Info.plist")] for name in names
                 if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", name)]
        if len(roots) != 1:
            raise ValueError("Expected exactly one app in the IPA")
        root = roots[0]
        info = plistlib.loads(archive.read(root + "Info.plist"))
        executable = info.get("CFBundleExecutable", "")
        if not executable or "/" in executable or executable in (".", ".."):
            raise ValueError("Invalid app executable name")
        for required in (executable, "_CodeSignature/CodeResources", "embedded.mobileprovision"):
            if root + required not in names:
                raise ValueError("Archive is missing its executable, signature or profile")
        # Verify CMS integrity here; iOS performs the authoritative Apple trust/code-signature check.
        decoded = subprocess.run(
            ["openssl", "cms", "-verify", "-inform", "DER", "-noverify"],
            input=archive.read(root + "embedded.mobileprovision"),
            capture_output=True, check=True, timeout=15,
        ).stdout
        validate_profile(info, plistlib.loads(decoded), expected, team, udid)


async def install(path, expected, team):
    from pymobiledevice3.lockdown import create_using_usbmux
    from pymobiledevice3.services.installation_proxy import InstallationProxyService
    from pymobiledevice3.usbmux import list_devices

    devices = [device for device in await list_devices() if device.is_usb]
    if len(devices) != 1:
        raise ValueError("Connect exactly one USB test iPhone")
    client = await create_using_usbmux(serial=devices[0].serial, autopair=False, connection_type="USB")
    try:
        if not client.paired:
            raise ValueError("The phone must be paired first")
        inspect_ipa(path, expected, team, devices[0].serial)
        async with InstallationProxyService(client) as proxy:
            await asyncio.wait_for(proxy.install_from_local(path), timeout=INSTALL_TIMEOUT_SECONDS)
            apps = await proxy.get_apps(bundle_identifiers=[expected])
            if expected not in apps:
                raise RuntimeError("Installation did not produce the expected app")
        print("Isolated test bundle installed and present on the phone")
    finally:
        await client.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=INSTALL_TARGETS)
    parser.add_argument("ipa", type=Path)
    args = parser.parse_args()
    logging.disable(logging.CRITICAL)
    try:
        asyncio.run(install(args.ipa.resolve(), INSTALL_TARGETS[args.kind],
                            os.environ.get("IOS_TEAM_ID", "")))
    except Exception as exc:
        # Service errors can contain personal device/signing details; don't echo them to CI.
        parser.exit(1, f"Installation failed ({type(exc).__name__}); no other app was removed.\n")
