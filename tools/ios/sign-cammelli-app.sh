#!/usr/bin/env bash
# Sign an unsigned Pandy or LiberPet IPA as its isolated test bundle.
set -euo pipefail
[[ $# == 2 ]] || { echo "usage: $0 pandy|liberpet UNSIGNED_IPA" >&2; exit 2; }
case $1 in
  pandy) bundle=io.github.gccammelli.pandy.testbed ;;
  liberpet) bundle=io.github.gccammelli.liberpet.testbed ;;
  *) echo "Only pandy or liberpet test bundles are allowed" >&2; exit 2 ;;
esac

project_dir=$(cd "$(dirname "$0")/../.." && pwd)
private="${IOS_PRIVATE_DIR:?Set IOS_PRIVATE_DIR to the private Apple signing folder}"
out="$project_dir/build/ios-private"
mkdir -p -m 700 "$out"
"$project_dir/build/ios-tools/signing/zsign" -q \
  -k "$private/dev-key.pem" -c "$private/dev-cert.pem" \
  -m "$private/$bundle.mobileprovision" -b "$bundle" \
  -o "$out/$1-signed.ipa" "$2"
echo "Signed isolated $1 test app is in build/ios-private/"
