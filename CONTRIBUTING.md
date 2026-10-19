# Contributing to Meowstache

Bug reports, feature requests and pull requests are welcome. Please keep changes focused: one problem or feature per issue and pull request.

## Report a bug or request a feature

Use the repository's issue forms and include enough detail to reproduce or understand the request. Remove passwords, tokens, serial numbers, private URLs, pet data and other personal information from logs and screenshots.

For security vulnerabilities, do not open a public issue. Follow [SECURITY.md](SECURITY.md).

## Make a change

1. Open or link an issue for non-trivial work so the intended behaviour is clear.
2. Create a branch from the target branch (`main`, or `local-mode` for ESPHome work).
3. Add or update tests for behaviour changes and run:

   ```bash
   ./gradlew testDebugUnitTest
   ```

4. If the change is user-facing, attach before-and-after screenshots to the pull request. Use a short video when motion or a multi-step interaction cannot be shown clearly in still images. Say “No visual change” when that is genuinely the case.
5. Describe the devices, Android/iOS versions, Home Assistant version and firmware used for any relevant manual or hardware test. Do not claim a real-device test unless it was actually performed.

All checks must pass. Generated or AI-assisted contributions are welcome, but the author remains responsible for understanding, testing and clearly describing the result.

Automation and contributors may prepare pull requests, but must never merge them. The maintainer reviews and explicitly approves every merge into a protected project branch.
