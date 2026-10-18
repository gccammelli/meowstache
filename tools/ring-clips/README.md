# Meowstache Ring clips

Saves **every** Ring recording of a litter-box visit into Home Assistant's media folder, for Meowstache's
[visit videos](../../docs/visit-videos.md). Each clip is named by the moment it started recording (UTC), so Meowstache
matches it to the right visit and plays the parts of a split visit in order.

Ring splits a long visit into several recordings, and Home Assistant's Ring integration only shows the latest one, so
the simple automation in the guide can miss the first part (usually the cat walking in). This helper asks Ring's own
history instead. It's only for Ring; with a camera that has a stream, use the guide's `camera.record` recipe.

It runs on any always-on computer with Python 3.10+ (a home server, a Raspberry Pi), and talks to Ring and to your
Home Assistant. Home Assistant does the downloading, through its **Downloader** integration (download folder `/media`),
so the helper never needs access to Home Assistant's disk. You need a Ring plan that keeps recordings.

## How it decides what to save

- It reads the robot's status from Home Assistant every 15 s (`robot_status_entity`, the Litter-Robot integration's
  status code by default). Recordings that overlap a time when a cat was in the robot, give or take `margin_s`, are
  saved. Set `robot_status_entity` to `null` to save every recording of the camera.
- It asks Ring for the camera's history only while a visit is recent (a cat in the last 20 minutes), at most every
  90 s, plus one check every 30 minutes. A budget caps it at 40 Ring requests an hour.
- Recordings already saved are remembered in `state_file`; recordings older than 6 hours are skipped.

## Setup

```sh
python3 -m venv venv && venv/bin/pip install ring-doorbell
```

`ring-clips.json` (every key but the two `ha_` ones is optional):

```json
{
  "camera": "Litter Robot 4",
  "ha_url": "http://homeassistant.local:8123",
  "ha_token_file": "ha-token",
  "robot_status_entity": "sensor.litter_robot_4_status_code"
}
```

| Key | Default | |
|---|---|---|
| `camera` | the only camera | the Ring camera's name, as in the Ring app |
| `ha_url`, `ha_token_file` | none | Home Assistant's address and a file holding a long-lived access token |
| `subdir` | `meowstache` | the folder inside Downloader's, which you enter in Meowstache › Visit Videos |
| `robot_status_entity` | `sensor.litter_robot_4_status_code` | in local mode, the robot's `sensor.<robot>_status` |
| `cat_states` | `["cd", "cst"]` | in local mode: `["Cat detected", "Waiting to cycle"]` |
| `margin_s` | `90` | how far before or after a visit a recording may start |
| `ring_token_file` | `ring-token.json` | written by `--login` |
| `state_file` | `ring-clips-state.json` | the recordings already saved |

Sign in to Ring once (your password is used once and not stored; the token it gets is saved instead, so keep that
file private):

```sh
venv/bin/python meowstache_ring_clips.py --config ring-clips.json --login
```

Then run it as a service, e.g. `~/.config/systemd/user/meowstache-ring-clips.service`:

```ini
[Unit]
Description=Meowstache Ring clips (every recording of each litter-box visit)
After=network-online.target

[Service]
WorkingDirectory=%h/.local/opt/meowstache-ring
ExecStart=%h/.local/opt/meowstache-ring/venv/bin/python -u meowstache_ring_clips.py --config ring-clips.json
Restart=always
RestartSec=30

[Install]
WantedBy=default.target
```

`systemctl --user enable --now meowstache-ring-clips`, and `journalctl --user -u meowstache-ring-clips -f` to watch it.
If you used the guide's Ring automation before, turn it off, or visits get each clip twice.

Tests: `python3 test_ring_clips.py`.
