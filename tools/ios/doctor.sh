#!/usr/bin/env bash
# Read-only readiness checks; do not print device identifiers or pairing records into CI logs.
set -euo pipefail
project_dir=$(cd "$(dirname "$0")/../.." && pwd)
ios_tools="$project_dir/build/ios-tools"
export APPIUM_HOME="$ios_tools/appium"
[[ -x "$ios_tools/python/bin/python" && -x "$ios_tools/node/node_modules/.bin/appium" ]] || {
  echo 'Run bash tools/ios/setup.sh first' >&2; exit 1;
}
"$ios_tools/node/node_modules/.bin/appium" --version
"$ios_tools/node/node_modules/.bin/appium" driver list --installed
if [[ ! -S /var/run/usbmuxd ]]; then
  echo 'USB iPhone service is not running. Connect/unlock the phone; usbmuxd must be available.'
  exit 2
fi
"$ios_tools/python/bin/python" - "$project_dir/tools/ios" <<'PY'
import asyncio
import logging
import sys
from pymobiledevice3.lockdown import create_using_usbmux
from pymobiledevice3.services.installation_proxy import InstallationProxyService
from pymobiledevice3.services.mobile_image_mounter import MobileImageMounterService
from pymobiledevice3.usbmux import list_devices
sys.path.insert(0, sys.argv[1])
from install import TEST_APP, WDA
logging.disable(logging.CRITICAL)

async def check():
    devices = [device for device in await list_devices() if device.is_usb]
    print(f'USB iOS devices found: {len(devices)}')
    if len(devices) != 1:
        print('Connect exactly one test iPhone for this readiness check.')
        return 2
    client = await create_using_usbmux(serial=devices[0].serial, autopair=False, connection_type='USB')
    try:
        version = client.all_values.get('ProductVersion', 'unknown')
        print('Model identifier:', client.all_values.get('ProductType', 'unknown'))
        print('iOS version:', version)
        print('Paired session:', bool(client.paired))
        if not client.paired:
            print('Unlock the iPhone and approve pairing before continuing.')
            return 2
        if not version.split('.')[0].isdigit() or int(version.split('.')[0]) < 18:
            print('Linux Appium sessions require iOS 18 or later.')
            return 2
        enabled = await client.get_value(domain='com.apple.security.mac.amfi', key='DeveloperModeStatus')
        print('Developer Mode enabled:', enabled)
        if not enabled:
            print('Enable Developer Mode on the iPhone before continuing.')
            return 2
        async with MobileImageMounterService(client) as mounter:
            mounted = any(image.get('IsMounted') and image.get('MountPath') == '/System/Developer'
                          for image in await mounter.copy_devices())
        print('Developer disk image mounted:', mounted)
        async with InstallationProxyService(client) as proxy:
            apps = await proxy.get_apps(bundle_identifiers=[TEST_APP, WDA])
        print('Isolated test app installed:', TEST_APP in apps)
        print('WebDriverAgent installed:', WDA in apps)
        if not mounted or TEST_APP not in apps or WDA not in apps:
            print('Not ready for UI tests: complete the missing prerequisites above.')
            return 2
        print('Device prerequisites ready. Run the smoke test to verify a live Appium session.')
        return 0
    finally:
        await client.close()

try:
    raise SystemExit(asyncio.run(check()))
except Exception as exc:
    print('Device readiness requires attention:', type(exc).__name__)
    raise SystemExit(2)
PY
