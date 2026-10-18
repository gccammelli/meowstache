# Visit videos

Point a camera at the litter box and Meowstache shows a ▶ on each visit, so you can see who it was. Your Home
Assistant records the clips and your phone plays them straight from it: Meowstache never uploads a video anywhere.

New in Meowstache 1.1, in the [preview builds](https://github.com/gccammelli/meowstache/releases/tag/preview-local-mode) for now.
Without Home Assistant there are no visit videos: something at home has to record the clips and hand them to your phone.

## Before you start

- **Home Assistant**, with your camera in it.
- **Your robot in Home Assistant**, so it knows when a cat is in: the **Litter-Robot** integration (signed in with your
  Whisker account), or in local mode the ESPHome firmware the app already uses.
- **Optional, to watch away from home:** an address for your Home Assistant that works outside your network (Home
  Assistant Cloud, or your own HTTPS address).

## Pick your path

| Your camera | Path | Time | Notes |
|---|---|---|---|
| Has a live stream in Home Assistant (RTSP/ONVIF, Reolink, Tapo, UniFi Protect, Frigate, Generic Camera…) | **A** | ~15 min | One clip per visit, starting just before the cat walks in. Everything in Home Assistant's web interface. |
| **Ring**, with a computer at home that's always on | **B** | ~45 min | Every recording of each visit, including the cat walking in. Needs some command-line comfort. |
| **Ring**, nothing else | **C** | ~10 min | Most visits get a video, but a long visit Ring splits in two may lose its beginning. |

Cloud-only cameras (Blink, Arlo, Nest, Eufy without RTSP) aren't covered yet.

All three paths save clips into Home Assistant's media folder `meowstache`, named by their start time in UTC
(`2026-10-15T17-09-33Z.mp4`); then do **[Turn it on in Meowstache](#turn-it-on-in-meowstache)**.

To add an automation in Home Assistant: **Settings › Automations & scenes › Create automation › ⋮ › Edit in YAML**,
paste, change the entity ids to yours, save. The examples use the Litter-Robot integration's
`sensor.litter_robot_4_status_code` and a camera called `camera.litter_box`; find yours under **Settings › Devices &
services › Entities**. In **local mode**, use the robot's `sensor.<robot>_status` instead, with `Cat detected` for `cd`
and `Waiting to cycle` for `cst`.

### A. A camera with a stream

1. In Home Assistant, open the camera's settings (**Settings › Devices & services › Entities › your camera › ⚙**) and
   turn on **Preload stream**, so a clip can start a few seconds before the cat is detected.
2. Add this automation:

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

3. Wait for a visit (or walk past the robot so it detects something) and check **Media › Local media › meowstache**
   has a clip.

### B. Ring, with the Ring clips helper

1. In Home Assistant: **Settings › Devices & services › Add integration › Downloader**, download folder `/media`.
2. On your always-on computer, set up **[Meowstache Ring clips](../tools/ring-clips/README.md)**: install it, write its
   small config file, sign in to Ring once, start it as a service. Its guide has every step, and a section on what the
   Ring sign-in gives it access to (best: a separate Ring account with only the litter-box camera shared).
3. If you set up path C before, turn that automation off, or visits get each clip twice.

You need a Ring plan that keeps recordings.

### C. Ring, simplest

1. In Home Assistant: **Settings › Devices & services › Add integration › Downloader**, download folder `/media`.
2. Add this automation (your Ring camera is `camera.<name>_live_view`):

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

Why it can miss the start: Home Assistant only shows Ring's latest recording, so when Ring splits a visit, the first
part can be replaced before it's saved. Path B fixes that. You need a Ring plan that keeps recordings.

## Turn it on in Meowstache

1. **Make an access token** (Whisker sign-in only; local mode already has one): in Home Assistant, click your name
   (bottom left) › **Security** › **Long-lived access tokens** › **Create token**, name it "Meowstache", copy it.
   Create it on the phone itself to avoid typing it: Home Assistant shows it once with a copy button.
2. In Meowstache: **Account › Visit Videos**:
   - **Home Assistant address** (Whisker sign-in only): its address at home, like `http://homeassistant.local:8123`
   - **Long-lived access token** (Whisker sign-in only): the one you just made
   - **Media folder:** `meowstache`
   - **Away address (optional):** the address that works outside home, like your Home Assistant Cloud address
3. **Save.** Meowstache checks the folder and says how many videos it found ("no videos yet" is fine before the first
   visit).

Visits with a clip now show a ▶, on the robot's timeline and in each cat's weight history. A clip shows up by itself
within a minute or two of a visit (Meowstache looks for it every 20 s for 10 minutes). It plays in the app, through a
link Home Assistant signs for an hour.

## If something's off

- **No ▶ on a visit:** check the clip is in **Media › Local media › meowstache** and its name is a UTC time like the
  ones above (other files are ignored). A clip matches the visit weighed from 5 minutes before it to 10 minutes after.
- **"Couldn't play the video" away from home:** add an **Away address** (step 2 of Turn it on); `homeassistant.local` and
  home IP addresses only work at home.
- **Ring videos miss the cat walking in:** that's path C's limit; switch to path B.

## Steps only you can do

If an AI assistant or a friend sets this up for you, they'll still need you for:

- signing in to Ring (path B: your password and the 2FA code Ring sends you),
- creating the Home Assistant access token, and typing the settings into Meowstache on your phone.

## Good to know

- Home Assistant doesn't delete old clips. Each is a few MB, so delete old ones now and then in **Media ›
  meowstache**.
- Ring hands over a recording a minute or two after the motion. Path C names the clip by that time, which the
  10-minute match window covers; path B names it by when it started.
- A visit split into several clips plays as one video on Android (one after another on iPhone); a part that's gone
  missing is left out.
- Back-to-back cats can share one clip.
- Want instant visit notifications on iPhone too? See [iPhone notifications](iphone-notifications.md).
