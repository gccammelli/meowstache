# Local mode test rig

Pretend Litter-Robots on the [ESPHome firmware](https://codeberg.org/Joseph-DiGiovanni/esphome-litter-robot), a real Home Assistant, and Meowstache's local mode on top. No hardware needed.

- `mock-lr4.yaml`: an ESPHome device for the PC ("host" platform). Same entity names as the real firmware, and the firmware's own cat tracker component and packages (pulled from Codeberg). It simulates a visit every few minutes, or when you press "Simulate Visit".
- `setup_ha.py`: sets up a fresh Home Assistant like a firmware user would (adds both robots, enables and renames the cat entities, sets starting weights) and prints a long-lived token.

```sh
uv venv -p 3.13 esph && VIRTUAL_ENV=esph uv pip install esphome
uv venv -p 3.13 ha && VIRTUAL_ENV=ha uv pip install homeassistant

# Two robots: Robbie (Mochi, Luna) and Bobbie (Luna, Pixel); weights in kg
esph/bin/esphome -s name mock-lr4-a -s port 6053 -s mac 02:00:00:00:00:0a -s cats "{5.17f, 3.90f}" -s visit_every 3min run --no-logs mock-lr4.yaml &
esph/bin/esphome -s name mock-lr4-b -s port 6054 -s mac 02:00:00:00:00:0b -s cats "{3.90f, 4.60f}" -s visit_every 4min run --no-logs mock-lr4.yaml &
mkdir -p config && printf 'default_config:\n' > config/configuration.yaml && ha/bin/hass -c config &
ha/bin/python setup_ha.py http://localhost:8123 > token     # once Home Assistant is up

# The app's own client and mapping against it (2 robots, 3 cats, a command round trip)
MEOWSTACHE_HA_URL=http://localhost:8123 MEOWSTACHE_HA_TOKEN=$(cat token) ./gradlew :shared:testDebugUnitTest --tests '*LiveHomeAssistant*'
```

Add `MEOWSTACHE_HA_USER_TOKEN=<a non-admin user's token>` to also check that a regular Home Assistant user can read and control (registry edits then say they need an admin).

On a phone over USB: `adb reverse tcp:8123 tcp:8123`, then connect to `http://localhost:8123`.
