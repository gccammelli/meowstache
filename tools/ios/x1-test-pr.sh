#!/usr/bin/env bash
# Install and smoke-test one exact, CI-green Meowstache PR head on the private iPhone rig.
set -euo pipefail

[[ $# == 1 && $1 =~ ^[1-9][0-9]*$ ]] || { echo "usage: $0 PR_NUMBER" >&2; exit 2; }
pr=$1
project_dir=$(cd "$(dirname "$0")/../.." && pwd)
private="${IOS_PRIVATE_DIR:-$HOME/secrets/max/apple}"
cd "$project_dir"

[[ $(gh api user --jq .login) == gccammelli ]] || { echo "GitHub must be authenticated as gccammelli" >&2; exit 1; }
read -r state cross owner repo sha < <(
  gh pr view "$pr" --repo gccammelli/meowstache \
    --json state,isCrossRepository,headRepository,headRepositoryOwner,headRefOid \
    --jq '[.state, .isCrossRepository, .headRepositoryOwner.login, .headRepository.name, .headRefOid] | @tsv'
)
[[ $state == OPEN && $cross == false && $owner == gccammelli && $repo == meowstache &&
   $sha =~ ^[0-9a-f]{40}$ ]] || { echo "PR is not an open same-repository Meowstache change" >&2; exit 1; }

marker="<!-- cammelli-ios-smoke:$sha -->"
if gh api "repos/gccammelli/meowstache/issues/$pr/comments" --paginate \
    --jq ".[] | select(.body | contains(\"$marker\")) | .id" | grep -q .; then
  echo "This exact PR head already has iPhone evidence"
  exit 0
fi

run_id=$(gh run list --repo gccammelli/meowstache --workflow build.yml --event pull_request \
  --commit "$sha" --limit 10 --json databaseId,conclusion,headSha \
  --jq 'map(select(.conclusion == "success")) | .[0].databaseId // empty')
[[ $run_id =~ ^[0-9]+$ ]] || { echo "No successful Build run exists for the exact PR head" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
gh run download "$run_id" --repo gccammelli/meowstache --name ios --dir "$work"
ipa=$(find "$work" -type f -name meowstache.ipa -print -quit)
[[ -n "$ipa" ]] || { echo "The successful run has no unsigned Meowstache IPA" >&2; exit 1; }

bash tools/ios/sign.sh "$ipa"
IOS_TEAM_ID=$(cat "$private/team-id") "$project_dir/build/ios-tools/python/bin/python" \
  tools/ios/install.py app build/ios-private/app-signed.ipa
IOS_PRIVATE_DIR="$private" tools/ios/x1-smoke-gate.sh

short=${sha:0:12}
gh pr comment "$pr" --repo gccammelli/meowstache --body "$marker
Physical iPhone local-demo smoke passed for commit `$short` using the unsigned IPA from successful Build run $run_id. The app was locally development-signed and installed under the isolated test bundle; no production app or data was touched."
