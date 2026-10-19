#!/usr/bin/env python3
"""Build upstream cat trackers, start a disposable HA, and run the real HTTP integration test."""
import os
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def main():
    root = ROOT / "build/local-mock"
    root.mkdir(parents=True, exist_ok=True)
    # Fail rather than reusing or mutating a developer's existing Home Assistant/robots.
    for port in (8123, 6053, 6054):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    version = json.loads((ROOT / "tools/upstream-lr4.json").read_text())["esphome_version"]
    for name, dependency, python in (("esph", f"esphome=={version}", "3.13"), ("ha314", "homeassistant==2026.10.2", "3.14")):
        environment = root / name
        if not (environment / "bin/python").exists():
            subprocess.run(["uv", "venv", "--python", python, str(environment)], check=True)
        subprocess.run(["uv", "pip", "install", "--python", str(environment / "bin/python"), dependency], check=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=root))
    source = ROOT / "tools/local-mock"
    shutil.copyfile(source / "mock-lr4.yaml", work / "mock-lr4.yaml")
    processes, logs = [], []

    def start(command, name):
        log = (work / f"{name}.log").open("w")
        logs.append(log)
        process = subprocess.Popen(command, cwd=work, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        return process

    def wait_url(url):
        for _ in range(120):
            if any(process.poll() is not None for process in processes):
                raise RuntimeError(f"Mock process exited; see {work}")
            try:
                urllib.request.urlopen(url, timeout=2).close()
                return
            except (OSError, urllib.error.URLError):
                time.sleep(1)
        raise TimeoutError(f"Home Assistant did not become ready; see {work}")

    try:
        for suffix, port, mac, cats in (("a", "6053", "0a", "{5.17f, 3.90f}"), ("b", "6054", "0b", "{3.90f, 4.60f}")):
            name = f"mock-lr4-{suffix}"
            subprocess.run([str(root / "esph/bin/esphome"), "-s", "name", name, "-s", "port", port,
                            "-s", "mac", f"02:00:00:00:00:{mac}", "-s", "cats", cats,
                            "compile", "mock-lr4.yaml"], cwd=work, check=True)
            start([str(work / f".esphome/build/{name}/.pioenvs/{name}/program")], name)
        config = work / "config"
        config.mkdir()
        (config / "configuration.yaml").write_text("default_config:\n")
        start([str(root / "ha314/bin/hass"), "--config", str(config)], "home-assistant")
        wait_url("http://127.0.0.1:8123/api/onboarding")
        token = subprocess.check_output([str(root / "ha314/bin/python"), str(source / "setup_ha.py"),
                                         "http://127.0.0.1:8123"], text=True).strip()
        if not token or "\n" in token:
            raise ValueError("Unexpected mock Home Assistant token response")
        if os.environ.get("GITHUB_ACTIONS"):
            print(f"::add-mask::{token}", flush=True)
        env = {**os.environ, "MEOWSTACHE_HA_URL": "http://127.0.0.1:8123", "MEOWSTACHE_HA_TOKEN": token}
        subprocess.run(["./gradlew", "--no-daemon", ":shared:testDebugUnitTest", "--rerun-tasks",
                        "--tests", "*LiveHomeAssistantTest.readsTheMockRobots"], cwd=ROOT, env=env, check=True)
        print("Real Home Assistant and upstream cat tracker integration passed")
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
