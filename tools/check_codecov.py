#!/usr/bin/env python3
"""Require 95% fully covered lines in Codecov's processed report for this commit."""

import json
import re
import sys
import time
import urllib.error
import urllib.request


def totals(report, commit):
    if report.get("commitid") != commit:
        raise ValueError("Codecov returned a different commit")
    if report.get("state") in {"error", "failed"}:
        raise ValueError("Codecov report processing failed")
    if report.get("state") != "complete":
        return None
    counts = report.get("totals") or {}
    values = [counts.get(key) for key in ("hits", "partials", "misses", "lines")]
    if any(type(value) is not int or value < 0 for value in values):
        raise ValueError("Codecov report has missing or invalid line totals")
    hits, partials, misses, lines = values
    if not lines or hits + partials + misses != lines:
        raise ValueError("Codecov report has empty or inconsistent line totals")
    return hits, partials, misses, lines


def passes(counts):
    hits, _, _, lines = counts
    return hits * 100 >= lines * 95


def self_test():
    commit = "a" * 40

    def report(hits, partials, misses):
        return {"commitid": commit, "state": "complete", "totals": {
            "hits": hits, "partials": partials, "misses": misses, "lines": hits + partials + misses}}

    assert passes(totals(report(95, 5, 0), commit))
    assert not passes(totals(report(94, 6, 0), commit))
    assert not passes(totals(report(74, 26, 0), commit))  # All lines executed is insufficient.
    assert not passes(totals(report(94999, 5001, 0), commit))  # Rounding cannot pass.
    assert totals({"commitid": commit, "state": "pending"}, commit) is None
    for invalid in [report(0, 0, 0), {**report(100, 0, 0), "commitid": "b" * 40},
                    {"commitid": commit, "state": "failed"},
                    {"commitid": commit, "state": "complete", "totals": {"hits": True}},
                    {"commitid": commit, "state": "complete", "totals": {"hits": 95, "partials": 6, "misses": 0, "lines": 100}}]:
        try:
            totals(invalid, commit)
        except ValueError:
            pass
        else:
            raise AssertionError("Invalid or stale Codecov data was accepted")
    print("Codecov gate self-test passed")


def main():
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return 0
    if len(sys.argv) != 3 or not re.fullmatch(r"[\w.-]+/[\w.-]+", sys.argv[1]) or not re.fullmatch(r"[a-f0-9]{40}", sys.argv[2]):
        raise ValueError("Usage: check_codecov.py OWNER/REPO FULL_COMMIT_SHA")
    repository, commit = sys.argv[1:]
    url = f"https://api.codecov.io/api/v2/github/{repository.split('/')[0]}/repos/{repository.split('/')[1]}/commits/{commit}/"
    deadline = time.monotonic() + 300
    while True:
        try:
            with urllib.request.urlopen(url, timeout=15) as response:
                counts = totals(json.load(response), commit)
            if counts:
                hits, partials, misses, lines = counts
                print(f"Codecov {commit}: {hits}/{lines} fully covered lines = {hits / lines:.2%}; {partials} partial, {misses} missed. Minimum: 95%.")
                return 0 if passes(counts) else 1
        except urllib.error.HTTPError as error:
            if error.code not in {404, 408, 429, 500, 502, 503, 504}:
                raise
        except (urllib.error.URLError, TimeoutError):
            pass
        if time.monotonic() >= deadline:
            raise ValueError("No completed Codecov report for this commit within five minutes")
        print("Waiting for Codecov to process this commit...", flush=True)
        time.sleep(10)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, urllib.error.URLError) as error:
        print(f"Codecov gate failed: {error}", file=sys.stderr)
        sys.exit(1)
