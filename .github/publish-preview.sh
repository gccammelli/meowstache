#!/usr/bin/env bash
# Puts a local-mode build on the "preview-local-mode" pre-release, replacing the previous one of the same kind.
#   .github/publish-preview.sh <file> <apk|ipa>
# Called by .github/workflows/build.yml (GH_TOKEN set); the notes come from .github/preview-notes.md.
set -euo pipefail
file=$1 ext=$2 tag=preview-local-mode
v=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts)
name="meowstache-$v.$ext"
cp "$file" "$name"
gh release upload "$tag" "$name" --clobber
# Then drop older builds of this kind, so the page only offers the newest.
gh release view "$tag" --json assets -q '.assets[].name' | grep "\.$ext\$" | grep -vxF "$name" |
  while read -r old; do gh release delete-asset "$tag" "$old" -y; done || true # none to drop
sed "s/{version}/$v/g" .github/preview-notes.md > "$RUNNER_TEMP/notes.md"
gh release edit "$tag" --title "Local mode preview ($v)" --notes-file "$RUNNER_TEMP/notes.md"
echo "published $name"
