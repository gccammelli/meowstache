#!/usr/bin/env bash
# A separate app id keeps the installed app's login/history untouched on real phones.
set -euo pipefail

cd "$(dirname "$0")/.."
if [[ $# == 0 ]]; then
  ./gradlew -Pmeowstache.testbed assembleDebug
  set -- app/build/outputs/apk/debug/app-debug.apk
fi
exec python3 tools/android-smoke.py "$@"
