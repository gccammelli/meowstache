"""Safety checks run without an iPhone or signing credentials."""
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from pathlib import Path
import plistlib
import tempfile
import unittest
import zipfile

from install import inspect_ipa, LIBERPET, PANDY, TEST_APP, validate_profile, WDA


class InstallationSafety(unittest.TestCase):
    def setUp(self):
        self.team = "ABCDE12345"
        self.udid = "fixture-phone"
        self.info = {"CFBundleIdentifier": TEST_APP}
        self.profile = {
            "TeamIdentifier": [self.team],
            "ApplicationIdentifierPrefix": ["OLDPREFIX1"],
            "Entitlements": {
                "application-identifier": "OLDPREFIX1." + TEST_APP,
                "com.apple.developer.team-identifier": self.team,
                "get-task-allow": True,
            },
            "ProvisionedDevices": [self.udid],
            "ExpirationDate": datetime.now(timezone.utc) + timedelta(days=1),
        }

    def check_profile(self, info=None, profile=None, expected=TEST_APP, team=None):
        validate_profile(info if info is not None else self.info,
                         profile if profile is not None else self.profile,
                         expected, self.team if team is None else team, self.udid)

    def test_valid_development_profile_and_legacy_prefix(self):
        self.check_profile()
        info = {"CFBundleIdentifier": WDA}
        profile = deepcopy(self.profile)
        profile["Entitlements"]["application-identifier"] = "OLDPREFIX1." + WDA
        self.check_profile(info=info, profile=profile, expected=WDA)

        for bundle in (PANDY, LIBERPET):
            info = {"CFBundleIdentifier": bundle}
            profile = deepcopy(self.profile)
            profile["Entitlements"]["application-identifier"] = "OLDPREFIX1." + bundle
            self.check_profile(info=info, profile=profile, expected=bundle)

    def test_never_accept_production_or_other_bundle(self):
        for bundle in (TEST_APP.removesuffix(".testbed"), "com.apple.Preferences", WDA):
            with self.subTest(bundle=bundle), self.assertRaises(ValueError):
                self.check_profile(info={"CFBundleIdentifier": bundle})
        with self.assertRaises(ValueError):
            self.check_profile(expected="com.apple.Preferences")

    def test_order_number_is_not_a_team_id(self):
        for team in ("", "W1234567890", "../foo", "abcde12345"):
            with self.subTest(team=team), self.assertRaises(ValueError):
                self.check_profile(team=team)

    def test_reject_expired_wrong_team_device_and_distribution(self):
        variants = [
            {"ExpirationDate": datetime.now(timezone.utc) - timedelta(seconds=1)},
            {"ExpirationDate": None},
            {"TeamIdentifier": ["OTHER12345"]},
            {"ProvisionedDevices": ["another-phone"]},
        ]
        for change in variants:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.check_profile(profile=self.profile | change)
        for change in ({"application-identifier": "OLDPREFIX1.*"},
                       {"get-task-allow": False},
                       {"com.apple.developer.team-identifier": "OTHER12345"}):
            profile = deepcopy(self.profile)
            profile["Entitlements"].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.check_profile(profile=profile)

    def test_unsigned_archive_rejected_before_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unsigned.ipa"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("Payload/Meowstache.app/Info.plist", plistlib.dumps(
                    self.info | {"CFBundleExecutable": "Meowstache"}))
                archive.writestr("Payload/Meowstache.app/Meowstache", b"unsigned")
            with self.assertRaises(ValueError):
                inspect_ipa(path, TEST_APP, self.team, self.udid)


if __name__ == "__main__":
    unittest.main()
