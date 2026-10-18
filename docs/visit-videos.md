# Visit videos

Point a camera at the litter box and Meowstache shows a play button on each visit, so you can see who it was. It works
with any camera your Home Assistant has: Ring, a webcam, a Generic Camera, and others.

It's new in Meowstache 1.1, in the [preview builds](https://github.com/gccammelli/meowstache/releases/tag/preview-local-mode) for now.

Home Assistant does the recording. An automation saves a clip into a media folder when a cat shows up. Meowstache lists
that folder and matches each clip to the visit nearest in time (from 5 minutes before the weighing to 10 after). The
video goes from your Home Assistant to your phone, and Meowstache never uploads it anywhere.

Clip names are their start time in UTC, like `2026-10-15T17-09-33Z.mp4`; Meowstache ignores other files.

## 1. Save a clip for each visit

Pick the recipe for your camera. In Home Assistant, go to **Settings › Automations & scenes › Create automation ›
⋮ › Edit in YAML**, paste the recipe and change the entity ids to yours. The examples use the Litter-Robot
integration's `sensor.litter_robot_4_status_code`. In local mode (ESPHome firmware), use the robot's
`sensor.<robot>_status` instead, with `Cat detected` for `cd` and `Waiting to cycle` for `cst`.

### Ring

Ring already records each motion event; you need a Ring plan that keeps recordings, and Home Assistant's
**Downloader** integration (**Settings › Devices & services › Add integration › Downloader**, download folder `/media`).

**Recommended: [Meowstache Ring clips](../tools/ring-clips/README.md)**, a small helper for an always-on computer. Ring
splits a long visit into several recordings and Home Assistant only shows the latest one, so the automation below can
miss the first part of a visit (usually the cat walking in). The helper asks Ring's history for every recording of each
visit and names each clip by when it started.

**Simpler, no extra computer:** the automation below saves Ring's latest recording when a cat caused it. Most visits
get their video; split visits may lose their beginning.

```yaml
alias: Meowstache visit video (Ring)
triggers:
  - trigger: state
    entity_id: camera.litter_robot_4_live_view
    attribute: last_video_id # a new Ring recording
conditions:
  - condition: state # only when a cat is in, or has just left
    entity_id: sensor.litter_robot_4_status_code
    state: [cd, cst]
actions:
  - action: downloader.download_file
    data:
      url: "{{ state_attr('camera.litter_robot_4_live_view', 'video_url') }}"
      subdir: meowstache
      filename: "{{ utcnow().strftime('%Y-%m-%dT%H-%M-%SZ') }}.mp4"
```

### Cameras with a stream (webcam, Generic Camera, most IP cameras)

Home Assistant records the stream itself, starting when the cat is detected. To include the few seconds before that,
turn on **Preload stream** in the camera's settings.

```yaml
alias: Meowstache visit video
triggers:
  - trigger: state
    entity_id: sensor.litter_robot_4_status_code
    to: cd # cat detected
actions:
  - action: camera.record
    target:
      entity_id: camera.litter_box
    data:
      filename: "/media/meowstache/{{ utcnow().strftime('%Y-%m-%dT%H-%M-%SZ') }}.mp4"
      duration: 240 # long enough for a visit, and a second cat right after
      lookback: 10
```

## 2. Turn it on in Meowstache

Go to **Account › Visit Videos**, enter the folder (`meowstache`) and save. When you're signed in with Whisker, also
enter your Home Assistant address and a long-lived access token (your Home Assistant profile › Security). Local mode
uses the Home Assistant it's already connected to.

Visits with a clip get a play button on the robot's timeline. Tapping it opens the clip in your phone's video player
or browser, through a link Home Assistant signs for an hour. Away from home, the address must be one your phone can
reach, like your Home Assistant Cloud address.

## Good to know

- Home Assistant doesn't delete old clips. Each one is a few MB, so delete old ones now and then in **Media ›
  meowstache**.
- Ring hands its recording over a minute or two after the motion. The automation names the clip by that time, which
  the 10-minute match window covers; the Ring clips helper names it by when it started.
- Ring splits a long visit into several recordings, saved about a minute apart. Meowstache plays them as one video
  (on iPhone, one after another); a part that's gone missing is left out.
- Back-to-back cats can share one clip.
