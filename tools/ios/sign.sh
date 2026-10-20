#!/usr/bin/env bash
# Sign the unsigned CI .ipa and the official unsigned WDA runner with the private set from provision.py.
# Usage: bash tools/ios/sign.sh <unsigned meowstache.ipa>. Signed IPAs carry the developer's name: keep them private.
set -euo pipefail
project_dir=$(cd "$(dirname "$0")/../.." && pwd)
ios_tools="$project_dir/build/ios-tools"
private="${IOS_PRIVATE_DIR:?Set IOS_PRIVATE_DIR to the private Apple signing folder}"
out="$project_dir/build/ios-private"
app=io.github.gccammelli.meowstache.testbed
mkdir -p -m 700 "$out"
sign() { "$ios_tools/signing/zsign" -q -k "$private/dev-key.pem" -c "$private/dev-cert.pem" -m "$private/$1.mobileprovision" -b "$1" -o "$3" "$2"; }

sign "$app" "$1" "$out/app-signed.ipa"
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir "$work/Payload"
unzip -q "$ios_tools/WebDriverAgentRunner-Runner.zip" -d "$work/Payload"
sign "$app.wda.xctrunner" "$work/Payload/WebDriverAgentRunner-Runner.app" "$out/wda-signed.ipa"
echo "Signed test app and WDA are in build/ios-private/"
