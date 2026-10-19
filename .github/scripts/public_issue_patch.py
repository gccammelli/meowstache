#!/usr/bin/env python3
"""Apply a public-issue agent patch across a narrow, secretless trust boundary."""
import argparse
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
MAX_PATCH_BYTES = 500_000
MAX_FILES = 8
MAX_CHANGED_LINES = 800


def git(*args, check=True):
    return subprocess.run(["git", *args], cwd=ROOT, check=check, capture_output=True)


def allowed(path, status, kind, existed):
    docs = path == "README.md" or (path.startswith("docs/") and path.endswith(".md"))
    if docs:
        return True, False, False
    if kind == "mobile":
        test = (path.startswith("shared/src/commonTest/") or
                path.startswith("shared/src/androidUnitTest/")) and path.endswith(".kt")
        source = any(path.startswith(prefix) for prefix in (
            "shared/src/commonMain/", "shared/src/androidMain/", "shared/src/iosMain/",
            "app/src/main/", "iosApp/iosApp/",
        )) and Path(path).suffix in {".kt", ".java", ".swift", ".xml"}
    else:
        test = path.startswith("tests/") and path.endswith(".py")
        source = path.startswith("pandy_slicer/") and path.endswith(".py") and path.count("/") == 1
    if test:
        return status == "A" and not existed, False, True
    return source, source, False


def apply_patch(patch, kind):
    if patch.stat().st_size > MAX_PATCH_BYTES:
        raise ValueError("candidate patch is too large")
    if git("status", "--porcelain", "--untracked-files=no").stdout.strip():
        raise ValueError("candidate validation requires a clean tracked checkout")
    git("apply", "--check", str(patch))
    git("apply", "--index", str(patch))
    git("diff", "--cached", "--check")

    raw = git("diff", "--cached", "--name-status", "-z", "HEAD").stdout.split(b"\0")
    entries = []
    index = 0
    while index < len(raw) and raw[index]:
        status = raw[index].decode()
        index += 1
        if status[0] not in {"A", "M"} or status[0] != status:
            raise ValueError(f"renames, copies and deletions are blocked: {status}")
        path = raw[index].decode()
        index += 1
        entries.append((status, path))
    if not entries or len(entries) > MAX_FILES:
        raise ValueError(f"candidate must change between 1 and {MAX_FILES} files")

    changed_lines = 0
    for row in filter(None, git("diff", "--cached", "--numstat", "-z", "HEAD").stdout.split(b"\0")):
        additions, deletions, _ = row.decode().split("\t", 2)
        if not additions.isdigit() or not deletions.isdigit():
            raise ValueError("binary patches are blocked")
        changed_lines += int(additions) + int(deletions)
    if changed_lines > MAX_CHANGED_LINES:
        raise ValueError(f"candidate changes more than {MAX_CHANGED_LINES} lines")

    changed_source = False
    new_test = False
    for status, path in entries:
        existed = git("cat-file", "-e", f"HEAD:{path}", check=False).returncode == 0
        ok, source, test = allowed(path, status, kind, existed)
        if not ok:
            raise ValueError(f"agent patch changes a protected path: {path}")
        mode = git("ls-files", "-s", "--", path).stdout.decode().split()[0]
        if mode != "100644":
            raise ValueError(f"only regular non-executable files are allowed: {path}")
        changed_source |= source
        new_test |= test
    if changed_source and not new_test:
        raise ValueError("runtime changes require a new regression-test file")
    print(f"Validated {len(entries)} files and {changed_lines} changed lines")


def self_test():
    assert allowed("README.md", "M", "mobile", True)[0]
    assert allowed("shared/src/commonMain/kotlin/example/Fix.kt", "M", "mobile", True)[1]
    assert allowed("shared/src/commonTest/kotlin/example/FixTest.kt", "A", "mobile", False)[2]
    assert not allowed("shared/src/commonTest/kotlin/example/OldTest.kt", "M", "mobile", True)[0]
    assert not allowed(".github/workflows/build.yml", "M", "mobile", True)[0]
    assert allowed("pandy_slicer/server.py", "M", "slicer", True)[1]
    assert allowed("tests/test_agent_fix.py", "A", "slicer", False)[2]
    assert not allowed("pandy_slicer/Dockerfile", "M", "slicer", True)[0]
    print("Public issue patch boundary self-test passed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("patch", nargs="?", type=Path)
    parser.add_argument("--kind", choices=("mobile", "slicer"), default="mobile")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.patch:
        apply_patch(args.patch.resolve(), args.kind)
    else:
        parser.error("provide a patch or --self-test")


if __name__ == "__main__":
    main()
