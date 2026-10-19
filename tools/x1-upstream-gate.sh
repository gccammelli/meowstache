#!/usr/bin/env bash
# Safely finish trusted upstream PRs using APKs built on GitHub and two USB test phones.
set -euo pipefail

repo=gccammelli/meowstache
root=$(cd "$(dirname "$0")/.." && pwd)
: "${CAMMELLI_CREDENTIALS_DIR:?Set CAMMELLI_CREDENTIALS_DIR to the local Cammelli credential store}"
enable_file="$CAMMELLI_CREDENTIALS_DIR/meowstache-auto-merge-enabled"

[[ $(gh api user --jq .login) == gccammelli ]] || exit 1
[[ -f "$enable_file" && $(<"$enable_file") == true ]] || exit 0

mapfile -t devices < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
physical=()
for serial in "${devices[@]}"; do
  if [[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') != 1 ]]; then
    physical+=("$serial")
  fi
done
devices=("${physical[@]}")
(( ${#devices[@]} >= 2 )) || exit 0

gh pr list --repo "$repo" --base main --state open --limit 10 \
  --json number,author,isCrossRepository,headRepository,headRepositoryOwner,headRefName,headRefOid,isDraft \
  --jq '.[] | select(.isDraft == false and .isCrossRepository == false and .author.login == "app/github-actions" and .headRepository.name == "meowstache" and .headRepositoryOwner.login == "gccammelli" and (.headRefName | startswith("codex/upstream-lr4-"))) | @base64' |
while IFS= read -r encoded; do
  [[ -n "$encoded" ]] || continue
  row=$(base64 -d <<< "$encoded")
  number=$(jq -r .number <<< "$row")
  branch=$(jq -r .headRefName <<< "$row")
  head=$(jq -r .headRefOid <<< "$row")
  [[ "$branch" =~ -([0-9]+)-([0-9]+)$ ]] || continue
  run_id=${BASH_REMATCH[1]}

  run=$(gh api "repos/$repo/actions/runs/$run_id")
  jq -e --arg repo "$repo" '
    .name == "Upstream Android" and .conclusion == "success" and
    .event == "workflow_dispatch" and .path == ".github/workflows/upstream-android.yml" and
    .head_branch == "main" and .head_repository.full_name == $repo
  ' <<< "$run" >/dev/null || continue
  parent=$(gh api "repos/$repo/commits/$head" --jq '.parents[0].sha')
  [[ $(gh api "repos/$repo/git/ref/heads/main" --jq .object.sha) == "$parent" ]] || continue

  work=$(mktemp -d)
  trap 'find "$work" -mindepth 1 -delete; rmdir "$work"' EXIT
  gh pr diff "$number" --repo "$repo" > "$work/candidate.diff"
  if ! python3 "$root/tools/autonomous-android.py" check-patch "$work/candidate.diff" ||
     ! python3 "$root/tools/autonomous-android.py" auto-merge-safe "$work/candidate.diff"; then
    continue
  fi
  gh run download "$run_id" --repo "$repo" --name upstream-android-evidence --dir "$work/artifact"
  gh run download "$run_id" --repo "$repo" --name upstream-pr-head --dir "$work/head"
  [[ $(<"$work/head/upstream-pr-head.txt") == "$head" ]]
  apk=$(find "$work/artifact" -name app-debug.apk -print -quit)
  [[ -n "$apk" ]]

  index=0
  for serial in "${devices[@]}"; do
    index=$((index + 1))
    ANDROID_SERIAL="$serial" ANDROID_TEST_LABEL="phone-$index" \
      bash "$root/tools/android-smoke.sh" "$apk"
  done

  [[ $(gh api "repos/$repo/git/ref/heads/main" --jq .object.sha) == "$parent" ]] || continue
  gh pr comment "$number" --repo "$repo" --body "X1 hardware gate passed on ${#devices[@]} dedicated Android test phones. The tested APK came from upstream workflow run $run_id; device identifiers are intentionally omitted."
  gh pr merge "$number" --repo "$repo" --squash --match-head-commit "$head"
  exit 0
done
