import subprocess
import tempfile
import unittest
from pathlib import Path

from smoke import APPS, Driver


class ForegroundGuard(unittest.TestCase):
    def driver(self, bundle, expected):
        driver = Driver(expected)
        calls = []
        def request(method, route, data=None):
            calls.append(route)
            if route == "/execute/sync":
                return {"bundleId": bundle}
            raise AssertionError("No UI command should run outside the test app")
        driver.request = request
        return driver, calls

    def test_refuse_personal_screens(self):
        for name, (expected, _exercise) in APPS.items():
            for bundle in ("com.apple.springboard", expected.removesuffix(".testbed"), None):
                driver, calls = self.driver(bundle, expected)
                with self.subTest(app=name, bundle=bundle), tempfile.TemporaryDirectory() as directory:
                    with self.assertRaises(RuntimeError):
                        driver.capture(Path(directory), "screen")
                    with self.assertRaises(RuntimeError):
                        driver.find("Pets", timeout=0.1)
                    self.assertEqual(list(Path(directory).iterdir()), [])
                    self.assertTrue(all(route == "/execute/sync" for route in calls))

    def test_only_mapped_test_apps_are_allowed(self):
        self.assertEqual(set(APPS), {"meowstache", "pandy", "liberpet"})
        for name, (bundle, exercise) in APPS.items():
            with self.subTest(app=name):
                self.assertTrue(bundle.endswith(".testbed"))
                self.assertTrue(callable(exercise))
                driver, _calls = self.driver(bundle, bundle)
                driver.foreground()
                wrong = next(other for other, _exercise in APPS.values() if other != bundle)
                driver, _calls = self.driver(wrong, bundle)
                with self.assertRaises(RuntimeError):
                    driver.foreground()

    def test_gate_rejects_extra_or_unknown_arguments(self):
        gate = Path(__file__).with_name("x1-smoke-gate.sh")
        for args in (["unknown"], ["meowstache", "extra"]):
            result = subprocess.run(["bash", gate, *args], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn("Usage:", result.stderr)

    def test_gate_waits_for_a_published_tunnel_and_tracks_its_root_pid(self):
        gate = Path(__file__).with_name("x1-smoke-gate.sh").read_text()
        self.assertIn("tunnel.pid", gate)
        self.assertIn(".tunnels | length > 0", gate)
        self.assertIn("setsid --fork --wait", gate)
        self.assertIn('sudo -n kill -- "-$tunnel_pid"', gate)

    def test_launch_waits_for_the_selected_test_app(self):
        bundle = APPS["pandy"][0]
        driver = Driver(bundle)
        foreground = iter(["com.apple.springboard", bundle])
        driver.execute = lambda _script, _args: {"bundleId": next(foreground)}
        driver.wait_foreground(timeout=1)


if __name__ == "__main__":
    unittest.main()
