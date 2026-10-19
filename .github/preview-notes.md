**Preview: local mode** for Litter-Robots running the community [ESPHome firmware](https://codeberg.org/Joseph-DiGiovanni/esphome-litter-robot), read through your own Home Assistant. No cloud. This is {version}, built from the [local-mode](https://github.com/gccammelli/meowstache/tree/local-mode) branch; every push there updates it.

- Always the newest beta: [meowstache-beta.apk](https://github.com/gccammelli/meowstache/releases/download/preview-local-mode/meowstache-beta.apk) and [meowstache-beta.ipa](https://github.com/gccammelli/meowstache/releases/download/preview-local-mode/meowstache-beta.ipa) (links you can share).
- Android: the `.apk` below. It's signed with a test key, so if you have Meowstache 1.0.x installed, uninstall it first.
- iPhone: the `.ipa` below (it follows the Android build by up to an hour), to sideload with SideStore/AltStore.
- On first launch pick **Local: Home Assistant**, enter your Home Assistant address and a long-lived access token. Or tap **Try the demo** to see it without any hardware.
- Test rig: [tools/local-mock](https://github.com/gccammelli/meowstache/tree/main/tools/local-mock).

**New in beta16:** tapping a "not available" feature explains it is only in Whisker's official app, with a button to hide them all; Account › Show unavailable features brings them back (its ⓘ explains why, and the ESPHome firmware route to full control).

**New in beta15:** fixes beta14 crashing when a video opened.

**New in beta14:** on Android a split visit plays as one video, with one progress bar across its parts; a part that's been deleted or failed to download is left out instead of stopping the video. A visit's video now appears by itself when the camera saves it (checked every 20 s for 10 minutes after the visit), with no need to reopen the app.

**New in beta13:** a cat's visit is announced the moment it's weighed (it used to wait for the clean cycle, several minutes later); the visit length is added to the same notification, quietly, once the cycle starts.

**New in beta12:** a visit Ring split into several recordings plays all of them, back to back (the entrance was missing before); a built-in player on iPhone; no star-sign badge.

**New in beta7:** visit videos ([#3](https://github.com/gccammelli/meowstache/issues/3)): with a camera on the litter box and a Home Assistant automation saving a clip of each visit, visits get a play button (Ring or any camera Home Assistant has; works signed in with Whisker too). [Setup guide](https://github.com/gccammelli/meowstache/blob/main/docs/visit-videos.md). Also: realistic demo visit lengths, clearer Cat Visit Summary wording, and fewer requests to Whisker.

**Beta5** brought a smaller app, visit lengths on the timeline, the Cat Visit Summary notification, back-to-back visits split per cat and background notifications in local mode.

Tested against a real Home Assistant with simulated robots running the firmware's cat tracker, not yet on a flashed robot. Something looks off? **Account › Home Assistant diagnostics** saves a file with what the app reads (no token or address); please attach it. Reports welcome: [issues](https://github.com/gccammelli/meowstache/issues) or meowstache.app@gmail.com.
