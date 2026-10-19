# iPhone test rig on Linux

Bootstrap the local tools with `bash tools/ios/setup.sh`, then check readiness
with `bash tools/ios/doctor.sh`. Dependencies are pinned and installed under
ignored `build/ios-tools/`; no global npm upgrade is needed.

This is bootstrap tooling, not an activated test/release pipeline. The intended
path is a private local agent on Linux, hosted macOS compilation, private signing,
and Appium controlling the USB-connected iPhone from Linux. A local zsign binary
is installed for Linux x86-64, pinned to v1.1.2 with its release SHA-256 verified.
Local signing is an option to keep keys off hosted runners, but has **not** yet
been validated with a paid profile or an installed WDA on this iOS version.

## One-time setup

1. Connect the iPhone to Linux by USB, unlock it, accept Trust This Computer,
   and enable Developer Mode under Settings → Privacy & Security when available.
   Developer Mode activation can require a reboot and on-device confirmation.
   Linux XCUITest sessions require iOS 18 or newer.
2. Record the device UDID privately and register it in the Apple Developer
   account. Keep the Team ID and device identifiers out of tracked files.
3. Create/register separate development identifiers for the Meowstache test app
   (`io.github.gccammelli.meowstache.testbed`) and the WebDriverAgent runner
   (`io.github.gccammelli.meowstache.testbed.wda.xctrunner`). Use a test app identifier distinct from the
   installed app, so test resets never erase real history or credentials.
   `BUNDLE_ID` in the Xcode configuration is the complete identifier; `TEAM_ID`
   controls signing independently and is not appended to the identifier.
4. Create an Apple Development signing certificate and development provisioning
   profiles covering the registered phone and the app/WDA identifiers. Keep
   private keys in private credential storage; never put them in source control
   or paste them into a chat. Paid membership alone does not configure signing.
5. Build and sign on a Mac or hosted macOS runner. Keep signed testing binaries,
   profiles, logs and pairing records private. Do not attach them to the existing
   public unsigned-IPA release. A private build repository or encrypted artifact
   transfer can deliver them to Linux without publishing the signing identity.
   Alternatively, compile unsigned on hosted macOS and sign locally using zsign.
   The official WDA releases include unsigned device builds; validate nested
   signatures, entitlements and actual iOS compatibility before adopting one.
6. Install the signed test app and WDA, mount the required developer disk image,
   create the RemoteXPC tunnel, then establish an Appium session. Prove a launch,
   screenshot and tap before enabling scheduled agent runs.

## Signing from Linux (App Store Connect API)

Steps 2–5 above are scripted. Create a Team API key once (App Store Connect →
Users and Access → Integrations → App Store Connect API, role **Admin**), and keep
the `.p8`, Key ID and Issuer ID in the private credential directory, never here.

```sh
export IOS_TEAM_ID=... ASC_KEY_ID=... ASC_ISSUER_ID=... ASC_KEY=/private/AuthKey_XXXX.p8
build/ios-tools/python/bin/python tools/ios/provision.py   # phone, bundle IDs, cert, two profiles
bash tools/ios/sign.sh /path/to/unsigned/meowstache.ipa    # → build/ios-private/{app,wda}-signed.ipa
```

`provision.py` registers the one USB phone, both test bundle IDs, an Apple
Development certificate (key generated locally) and one development profile per
bundle, replacing only its own `Meowstache testbed *` profiles. Its output goes to
`IOS_PRIVATE_DIR` (default outside the repository). The unsigned `.ipa` is the one
CI builds; `sign.sh` re-signs it as the testbed bundle. Signed IPAs and the
certificate carry the account holder's real name: never publish them.

## Installed command paths

From the repository root:

```sh
build/ios-tools/python/bin/pymobiledevice3 --help
APPIUM_HOME="$PWD/build/ios-tools/appium" build/ios-tools/node/node_modules/.bin/appium driver list --installed
```

Once the phone is paired, the driver can enumerate it with
`appium driver run xcuitest list-real-devices`. Device enumeration can print
personal device names/UDIDs, so keep the result private. Use the same
`APPIUM_HOME` for every Appium command, including commands run with sudo.

RemoteXPC tunnel creation requires privileges to create a TUN/TAP interface:
`appium driver run xcuitest tunnel-creation`. Its process must remain running
alongside the Appium server. These tools are installed, but no privileged tunnel,
Appium listener, phone pairing, app installation or reset is started by setup.sh.

For a preinstalled WDA session, set `platformName=iOS`,
`appium:automationName=xcuitest`, the exact `appium:udid` and
`appium:platformVersion`, `appium:usePreinstalledWDA=true`, the signed WDA bundle
identifier, and the test app's bundle identifier. Keep the server bound to
localhost. Do not use `fullReset` or run tests against the normal app's bundle ID.

TestFlight is an optional delivery channel for human testing. It does not replace
WDA provisioning or the Linux device-control setup used for agentic UI testing.

## Installation and UI checks

Keep the real Team ID in private environment configuration as `IOS_TEAM_ID`.
The installer checks the exact bundle ID, development profile, team, expiration
and registered phone before sending the IPA to iOS for authoritative signature
and Apple trust validation. Local CMS verification checks signature integrity,
not Apple's certificate trust chain. It never uninstalls an app or retries by
deleting another app on an installation failure.

```sh
build/ios-tools/python/bin/python tools/ios/install.py app build/ios-private/app-signed.ipa
build/ios-tools/python/bin/python tools/ios/install.py wda build/ios-private/wda-signed.ipa
# Keep the RemoteXPC tunnel running separately, then start Appium on loopback only:
APPIUM_HOME="$PWD/build/ios-tools/appium" build/ios-tools/node/node_modules/.bin/appium --address 127.0.0.1 --port 4723
# In another terminal:
build/ios-tools/python/bin/python tools/ios/smoke.py
```

The UI harness uses the preinstalled WDA with its exact `.xctrunner` ID. It
refuses UI commands/captures when the foreground app is not the test app, does
not reset app data or automatically accept alerts, and writes private evidence
under ignored `build/ios-smoke/`. It checks local-demo onboarding/navigation,
robots, pets, history and a light-setting command/readback. A repeat run can
reuse an existing local-demo session. This is **not** proof of communication
with a real Home Assistant or ESP32, production upgrades, notifications or
background behavior; those remain separate required scenarios for full E2E.
WDA has broad device-control powers: a foreground guard reduces accidental
captures but cannot provide an OS-level privacy boundary. Don't use the phone
interactively during a test. Passcode entry, any new trust dialogs and system
updates may still require the owner.

Run the offline safety tests:

```sh
build/ios-tools/python/bin/python -m unittest discover -s tools/ios -p 'test_*.py' -v
```

Last verified on X1 (2026-10-18): USB pairing, Developer Mode, personalized iOS 27
developer disk image mount, and a published RemoteXPC tunnel. The temporary
tunnel was stopped afterward. Signed app/WDA installation and actual UI tests
have not run. Enrollment activation, signing credentials and project write/build
access remain prerequisites, not completed setup.

Official setup references:

- [Appium on Linux/Windows](https://appium.github.io/appium-xcuitest-driver/latest/guides/non-macos-hosts/)
- [Preinstalled WDA](https://appium.github.io/appium-xcuitest-driver/latest/guides/run-preinstalled-wda/)
- [RemoteXPC tunnels](https://appium.github.io/appium-xcuitest-driver/latest/guides/remotexpc-tunnels-real-devices/)
- [Apple development profiles](https://developer.apple.com/help/account/provisioning-profiles/create-a-development-provisioning-profile)
