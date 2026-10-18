# LR4/2026.10.0 compatibility baseline

Meowstache local mode is tested against the community firmware's
[LR4/2026.10.0 release](https://codeberg.org/Joseph-DiGiovanni/esphome-litter-robot/releases/tag/LR4%2F2026.10.0),
built upstream with ESPHome 2026.9.1.

The baseline covers robot and cat discovery, per-cat weight/visit history,
individual tracker resets, Hopper Empty/Hopper Status, Auto Cycle and existing
local controls. The Android UI uses the same local-mode backend in its demo.

Upstream's cat-tracker rewrite can reset firmware weight/visit counts if entity
names were customized in YAML. Upstream says built-in tracker entities migrate
automatically otherwise. Review the upstream release notes and back up Home
Assistant before upgrading. Meowstache cannot prevent a reset performed by the
firmware; its on-phone history is retained independently.

The firmware also adds optional waste identification, counts and weights.
Meowstache does not yet present those measurements in dedicated charts or
alerts. It preserves normal cat weight/visit tracking when those entities exist.
This baseline does not enable, install or flash optional firmware features.
