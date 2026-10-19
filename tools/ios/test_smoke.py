import tempfile
import unittest
from pathlib import Path

from install import TEST_APP
from smoke import Driver


class ForegroundGuard(unittest.TestCase):
    def driver(self, bundle):
        driver = Driver()
        calls = []
        def request(method, route, data=None):
            calls.append(route)
            if route == "/execute/sync":
                return {"bundleId": bundle}
            raise AssertionError("No UI command should run outside the test app")
        driver.request = request
        return driver, calls

    def test_refuse_personal_screens(self):
        for bundle in ("com.apple.springboard", TEST_APP.removesuffix(".testbed"), None):
            driver, calls = self.driver(bundle)
            with self.subTest(bundle=bundle), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(RuntimeError):
                    driver.capture(Path(directory), "screen")
                with self.assertRaises(RuntimeError):
                    driver.find("Pets", timeout=0.1)
                self.assertEqual(list(Path(directory).iterdir()), [])
                self.assertTrue(all(route == "/execute/sync" for route in calls))

    def test_test_app_is_allowed(self):
        driver, _calls = self.driver(TEST_APP)
        driver.foreground()


if __name__ == "__main__":
    unittest.main()
