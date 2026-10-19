#!/usr/bin/env python3
"""Trusted boundaries for the autonomous upstream Android workflow."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "shared/src/commonMain/kotlin/io/github/gccammelli/meowstache/"
APP = "app/build.gradle.kts"
GENERATED = ["tools/upstream-lr4.json", "tools/local-mock/mock-lr4.yaml",
             "shared/src/commonTest/kotlin/io/github/gccammelli/meowstache/UpstreamLr4Contract.kt"]
ALLOWED = {PREFIX + name for name in (
    "HomeAssistant.kt", "DemoLocal.kt", "Models.kt", "ui/Robot.kt", "ui/Main.kt",
)} | set(GENERATED) | {APP, "docs/upstream-lr4.md",
    "shared/src/commonTest/kotlin/io/github/gccammelli/meowstache/UpstreamCompatibilityTest.kt"}
ADDITIVE_TEST = "shared/src/commonTest/kotlin/io/github/gccammelli/meowstache/UpstreamCompatibilityTest.kt"
MANUAL_REVIEW = {PREFIX + name for name in (
    "HomeAssistant.kt", "DemoLocal.kt", "Models.kt", "ui/Robot.kt", "ui/Main.kt",
)} | {ADDITIVE_TEST}


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def bumped(text):
    text, count = re.subn(r"versionCode = (\d+)", lambda m: f"versionCode = {int(m[1])+1}", text)
    if count != 1:
        raise ValueError("Expected one Android versionCode")
    text, count = re.subn(r'versionName = "(\d+\.\d+\.\d+-beta)(\d+)"',
                         lambda m: f'versionName = "{m[1]}{int(m[2])+1}"', text)
    if count != 1:
        raise ValueError("Autonomous releases currently target the local-mode beta series")
    return text


def allowed_patch(patch):
    records = subprocess.check_output(["git", "apply", "--numstat", "-z", str(patch)], cwd=ROOT).split(b"\0")
    paths = []
    for record in filter(None, records):
        additions, deletions, path = record.decode().split("\t", 2)
        if path not in ALLOWED or not additions.isdigit() or not deletions.isdigit():
            raise ValueError(f"Agent patch changes a protected path or binary file: {path}")
        if path == ADDITIVE_TEST and int(deletions):
            raise ValueError("Existing upstream compatibility assertions may not be removed")
        paths.append(path)
    if not set(GENERATED + [APP, "docs/upstream-lr4.md"]) <= set(paths):
        raise ValueError("Candidate must update the firmware lock, fixture, mock pin, version, and release notes")


def auto_merge_safe(patch):
    records = subprocess.check_output(["git", "apply", "--numstat", "-z", str(patch)], cwd=ROOT).split(b"\0")
    changed = {record.decode().split("\t", 2)[2] for record in filter(None, records)}
    blocked = sorted(changed & MANUAL_REVIEW)
    if blocked:
        raise ValueError("Runtime networking or UI changes require maintainer review: " + ", ".join(blocked))
    print("Candidate is within the non-UI auto-merge tier")


def apply(patch, tag):
    if git("status", "--porcelain", "--untracked-files=no").strip():
        raise ValueError("Candidate validation requires a clean checkout")
    allowed_patch(patch)
    old_app = (ROOT / APP).read_text()
    subprocess.run(["git", "apply", "--index", str(patch)], cwd=ROOT, check=True)
    for line in git("diff", "--cached", "--raw").decode().splitlines():
        if line.split()[1] != "100644":
            raise ValueError("Candidate must contain regular source files; deletion/symlink changes are blocked")
    if (ROOT / APP).read_text() != bumped(old_app):
        raise ValueError("Agent modified Android build configuration beyond the expected version bump")
    # Regenerate from Codeberg independently; an agent cannot forge its own upstream evidence.
    expected = {path: (ROOT / path).read_bytes() for path in GENERATED}
    subprocess.run(["python3", "tools/upstream-lr4.py", "--update", "--tag", tag], cwd=ROOT, check=True)
    for path, content in expected.items():
        if (ROOT / path).read_bytes() != content:
            raise ValueError(f"Candidate changed generated upstream evidence: {path}")
    print("Candidate patch scope, version and upstream evidence verified")


def self_test():
    value = 'versionCode = 20\nversionName = "1.1.0-beta16"\n'
    assert bumped(value) == 'versionCode = 21\nversionName = "1.1.0-beta17"\n'
    assert ".github/workflows/build.yml" not in ALLOWED
    assert "tools/android-smoke.py" not in ALLOWED
    def diff(path):
        return f"diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n@@ -1 +1 @@\n-before\n+after\n"
    with tempfile.TemporaryDirectory() as work:
        patch = Path(work) / "candidate.diff"
        valid = "".join(diff(path) for path in GENERATED + [APP, "docs/upstream-lr4.md"])
        patch.write_text(valid)
        allowed_patch(patch)
        auto_merge_safe(patch)
        for path in (".github/workflows/build.yml", "tools/android-smoke.py",
                     PREFIX + "Repo.kt", "shared/src/commonTest/kotlin/io/github/gccammelli/meowstache/LocalModeTest.kt"):
            patch.write_text(valid + diff(path))
            try:
                allowed_patch(patch)
            except ValueError:
                pass
            else:
                raise AssertionError(f"Protected path was accepted: {path}")
        patch.write_text(valid + diff(ADDITIVE_TEST))
        try:
            allowed_patch(patch)
        except ValueError:
            pass
        else:
            raise AssertionError("Existing upstream assertions could be removed")
        patch.write_text(diff(PREFIX + "HomeAssistant.kt"))
        try:
            allowed_patch(patch)
        except ValueError:
            pass
        else:
            raise AssertionError("Incomplete upstream evidence was accepted")
        patch.write_text(valid + diff(PREFIX + "HomeAssistant.kt"))
        try:
            auto_merge_safe(patch)
        except ValueError:
            pass
        else:
            raise AssertionError("Sensitive runtime change was accepted for auto-merge")
    print("Autonomous workflow boundary self-test passed")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("bump")
    sub.add_parser("self-test")
    check = sub.add_parser("apply")
    check.add_argument("patch", type=Path)
    check.add_argument("--tag", required=True)
    patch_check = sub.add_parser("check-patch")
    patch_check.add_argument("patch", type=Path)
    merge_check = sub.add_parser("auto-merge-safe")
    merge_check.add_argument("patch", type=Path)
    args = parser.parse_args()
    if args.action == "bump":
        (ROOT / APP).write_text(bumped((ROOT / APP).read_text()))
    elif args.action == "apply":
        apply(args.patch.resolve(), args.tag)
    elif args.action == "check-patch":
        allowed_patch(args.patch.resolve())
    elif args.action == "auto-merge-safe":
        auto_merge_safe(args.patch.resolve())
    else:
        self_test()


if __name__ == "__main__":
    main()
