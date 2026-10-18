#!/usr/bin/env bash
# Local Linux iPhone tooling; dependencies stay in ignored build/, without global npm changes.
set -euo pipefail
project_dir=$(cd "$(dirname "$0")/../.." && pwd)
ios_tools="$project_dir/build/ios-tools"
mkdir -p "$ios_tools"
if [[ ! -x "$ios_tools/python/bin/python" ]]; then
  uv venv --python 3.13 "$ios_tools/python"
fi
uv pip install --python "$ios_tools/python/bin/python" pymobiledevice3==11.35.0
npm exec --yes --package=npm@10.9.4 -- npm install --prefix "$ios_tools/node" --no-audit --no-fund appium@3.8.0
export APPIUM_HOME="$ios_tools/appium"
if ! "$ios_tools/node/node_modules/.bin/appium" driver list --installed --json 2>/dev/null |
  "$ios_tools/python/bin/python" -c 'import json,sys; sys.exit(json.load(sys.stdin).get("xcuitest", {}).get("version") != "12.18.2")'; then
  npm exec --yes --package=npm@10.9.4 -- "$ios_tools/node/node_modules/.bin/appium" driver install xcuitest@12.18.2
fi
if [[ $(uname -s) == Linux && $(uname -m) == x86_64 ]]; then
  zsign_archive="$ios_tools/zsign-linux-x86_64.tar.gz"
  [[ -f "$zsign_archive" ]] || curl --fail --location --retry 3 \
    https://github.com/zhlynn/zsign/releases/download/v1.1.2/zsign-linux-x86_64.tar.gz \
    --output "$zsign_archive"
  echo "d55e6a8650949a260892a9687ac1dfe4a9c79c2e3ecfe00a0eeb09b7b694282e  $zsign_archive" | sha256sum --check --status
  mkdir -p "$ios_tools/signing"
  tar -xzf "$zsign_archive" -C "$ios_tools/signing" --no-same-owner zsign
  "$ios_tools/signing/zsign" --version
fi
echo 'iPhone tool environment ready. Run: bash tools/ios/doctor.sh'
