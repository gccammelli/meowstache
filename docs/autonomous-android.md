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
5. Gets a separate Codex review. If physical-device testing is enabled, it also
   runs the UI tests on every configured Android phone.
6. Creates a PR against `local-mode`, merges it after all gates pass, and
   explicitly dispatches the normal Build workflow. That workflow tests the
   merged code again, builds the signed APK, exercises that exact release APK on
   an emulator, then updates the existing `preview-local-mode` GitHub release.

The automatic release is the **Android local-mode preview**, using the existing
preview signing key. This does not create stable `v*` tags or promote the beta to
`main`. The existing iOS workflow is unchanged; iOS does not gate the Android
preview. LR3 alpha branches, LR5, prereleases and unreleased upstream commits are
not tracked by this first pipeline.

## One-time activation

Land the implementation on `local-mode`. Install the identical
`.github/workflows/upstream-android.yml` on the repository's **default branch**
(`main`) too: GitHub only schedules workflows present on the default branch.
The scheduled workflow deliberately checks out `local-mode` and pins the
candidate to the exact base commit detected at the start.

In repository Settings → Secrets and variables → Actions:

- Add `OPENAI_API_KEY` for Codex repair and review calls. GitHub-hosted Codex
  Actions use API billing; the desktop subscription is not their credential.
  Use a dedicated API project with an appropriate budget/usage limit.
- Retain the existing `BETA_KEYSTORE_BASE64` secret for preview signing. Stable
  tags continue to use the existing release-keystore secrets.
- Allow GitHub Actions to create pull requests. The workflow requests only the
  permissions each job needs. Repository rules must permit the bot to merge;
  the workflow never uses an administrator bypass.
- Set `AUTO_UPSTREAM_MERGE=false` to leave verified PRs for manual merging.
  Otherwise successful candidates are merged and released automatically.

Run **Actions → Upstream Android → Run workflow** once to verify credentials and
scheduling. If the tracked firmware is already current, only detection runs.
The current baseline is `LR4/2026.10.0`.

The agent has no GitHub write credential, signing key, real HA credentials or
physical-device access. It returns a structured patch as its final step.
Verification and publication use separate runners. The physical runner only
builds a patch after hosted tests and independent review pass; register it under
a dedicated OS account without unrelated credentials.

## Physical Android test rig

Hosted Android emulator tests work without an X1 runner. To make real phones an
additional required gate, register a repository-specific GitHub Actions runner
on the test computer, with labels `self-hosted`, `Linux`, `meowstache-android`.
Install Java 17, Android SDK/platform/build tools, Python 3.13+, `adb`, and `jq`;
set `ANDROID_HOME`, accept Android SDK licenses, and authorize USB debugging on
the dedicated test phones. The runner must be online before enabling this gate.

Set repository secret `ANDROID_TEST_SERIALS` to their ADB serials, one per line,
then set variable `ANDROID_HARDWARE_TESTS=true`. Keep device identifiers out of
source control. If an enabled phone test fails, publication stops. Use dedicated,
unlocked test devices: an unattended test cannot unlock a passcode screen.

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
check retries it. A concurrent change to `local-mode` blocks merging the stale
candidate; rerun against the new base. Workflow retries use distinct bot branches
to avoid overwriting a previous candidate. Set `AUTO_UPSTREAM_MERGE=false` before
rerunning if a maintainer is investigating an existing candidate PR.

If merging succeeded but the subsequent Build failed, rerun **Build** on
`local-mode`: the baseline already advanced, so detection will not repeat the
repair. The published Android preview remains the last APK whose release tests
passed. Older app versions remain recoverable from git/release history.

The entity inventory is extracted from literal named entities across the LR4
packages; it is not an ESPHome configuration evaluator or proof that every
optional package is active. The agent reviews package wiring and state/command
semantics, while the real cat tracker integration and Android tests provide
independent execution checks. No test in this pipeline validates motor/safety
behavior on a flashed physical Litter-Robot.
