# Autonomous Android maintenance

The `Upstream Android` GitHub Actions workflow checks Codeberg every day at
07:23 UTC for the newest published, stable **LR4** firmware release. No new
release means no agent calls, PRs, releases, or notifications. Failures remain
visible in GitHub Actions; enable GitHub's failed-workflow notifications.

For a new release it:

1. Downloads the tagged source, records its SHA-256, extracts its entity inventory
   and declared ESPHome compiler version, and reports additions/removals and
   upstream breaking changes.
2. Advances the mock pin and Android beta version. Codex inspects the source,
   adapts the local-mode implementation, adds regression tests and writes
   `docs/upstream-lr4.md` with migration instructions and feature coverage.
3. Passes the candidate patch to a fresh runner. The trusted validator rejects
   changes outside the local-mode allowlist, changes to existing tests or the
   automation itself, binary/symlink/deletion changes, and any forged generated
   upstream evidence. The only permitted build-file change is the version bump.
4. Runs the existing unit/contract tests, builds two ESPHome host simulators with
   upstream's actual cat tracker, starts a disposable real Home Assistant, and
   tests discovery, pets, controls, sleep schedules and tracker management over
   HTTP/WebSocket. Android emulator tests exercise onboarding, local demo,
   devices, history, pets, and a light-setting command/readback through the UI.
5. Gets a separate Codex review, then creates a verified PR against `main`.
6. The X1 gate downloads the already-built test APK without executing candidate
   build code locally, runs it on at least two connected Android phones, and
   merges only the non-networking, non-UI tier when every gate passes. It then
   relies on the normal authenticated push event to start Build. That workflow tests the
   merged code again, builds the signed APK, exercises that exact release APK on
   an emulator, then updates the existing `preview-local-mode` GitHub release.

The automatic release is the **Android preview** (`preview-local-mode`, built from `main`), using the existing
preview signing key. It never creates a stable `v*` tag: releases stay a
maintainer decision. The existing iOS workflow is unchanged; iOS does not gate the Android
preview. LR3 alpha branches, LR5, prereleases and unreleased upstream commits are
not tracked by this first pipeline.

## One-time activation

Everything lives on `main`, the only long-lived branch (GitHub only schedules
workflows on the default branch). The workflow pins the candidate to the exact
`main` commit detected at the start. Between releases `main` carries a beta
version (`X.Y.Z-betaN`); a stable release is a `vX.Y.Z` tag.

In repository Settings → Secrets and variables → Actions:

- Add `OPENAI_API_KEY` for Codex repair and review calls. GitHub-hosted Codex
  Actions use API billing; the desktop subscription is not their credential.
  Use a dedicated API project with an appropriate budget/usage limit.
- Retain the existing `BETA_KEYSTORE_BASE64` secret for preview signing. Stable
  tags continue to use the existing release-keystore secrets.
- Allow GitHub Actions to create pull requests. The workflow requests only the
  permissions each job needs. The X1 gate merges with the `gccammelli` account
  and never uses an administrator bypass.
- The local enable marker `$CAMMELLI_CREDENTIALS_DIR/meowstache-auto-merge-enabled`
  must contain `true`. Without it, verified PRs remain open. Networking and UI
  changes always require maintainer review.

Run **Actions → Upstream Android → Run workflow** once to verify credentials and
scheduling. If the tracked firmware is already current, only detection runs.
The current baseline is `LR4/2026.10.0`.

The agent has no GitHub write credential, signing key, real HA credentials or
physical-device access. It returns a structured patch as its final step.
Verification and publication use separate GitHub-hosted runners. Candidate code
never executes on X1: the local gate only installs the validated APK artifact on
dedicated phones.

## Physical Android test rig

Do not register X1 as a self-hosted runner for this public repository: a hostile
pull request could target it. Instead, `tools/x1-upstream-gate.sh` polls verified
upstream PRs, checks their originating GitHub run, downloads its APK artifact and
tests every connected ADB device. It requires at least two phones and keeps their
serials out of source control. Create the local enable marker only after the user
timer is installed. Dedicated phones must remain connected, unlocked and USB-
authorized; an unattended test cannot unlock a passcode screen.

The user service runs from a detached, clean gate worktree under
`$XDG_DATA_HOME/cammelli/meowstache-gate`, not from the developer's
mutable checkout. Its oneshot has a 30-minute timeout and its calendar timer
recovers missed runs after reboot.

Real-device tests use `-Pmeowstache.testbed`, which installs
`io.github.gccammelli.meowstache.testbed` alongside the regular app. Only this
test app's data is reset. The production APK can only be reset by the harness
after verifying Android reports an emulator. Screenshots use demo data, with
generic artifact labels instead of device serials.

Run the same checks locally:

```sh
python3 tools/upstream-lr4.py --self-test
python3 tools/upstream-lr4.py --verify
python3 tools/autonomous-android.py self-test
./gradlew testDebugUnitTest assembleRelease
python3 tools/local-mock/check.py  # needs uv; starts/stops its own disposable services
ANDROID_SERIAL=<test-device> bash tools/android-smoke.sh
```

The integration runner refuses to start if ports 8123, 6053 or 6054 are in use.
It never reuses a user's Home Assistant. Its config, disposable token and logs
remain under ignored `build/local-mock/`; never upload that config directory.

## Failures and recovery

An agent cannot edit the gates, existing test assertions, dependencies, auth,
release scripts, or signing setup. Changes requiring those areas stop for a
maintainer. Known breaking changes that are handled by the app still need
explicit migration notes; a firmware reset cannot be undone by Meowstache.

A failed candidate leaves the tracked baseline unchanged, so the next daily
check retries it. A concurrent change to `main` blocks the X1 gate from
merging the stale candidate; rerun against the new base. Workflow retries use
distinct bot branches to avoid overwriting a previous candidate. Remove or
remove the local enable marker before rerunning if a maintainer is investigating
an existing candidate PR.

If merging succeeded but the subsequent Build failed, rerun **Build** on
`main`: the baseline already advanced, so detection will not repeat the
repair. The published Android preview remains the last APK whose release tests
passed. Older app versions remain recoverable from git/release history.

The entity inventory is extracted from literal named entities across the LR4
packages; it is not an ESPHome configuration evaluator or proof that every
optional package is active. The agent reviews package wiring and state/command
semantics, while the real cat tracker integration and Android tests provide
independent execution checks. No test in this pipeline validates motor/safety
behavior on a flashed physical Litter-Robot.
