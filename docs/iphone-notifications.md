# Instant notifications on iPhone, with Home Assistant

On Android, Meowstache keeps a live connection open and notifies you within seconds. iPhone apps can't: iOS suspends
them in the background, and instant alerts can only arrive as push notifications from a server. Meowstache has no
server on purpose (your account and data stay on your phone), so on iPhone its alerts only run while the app is open.

If you have **Home Assistant**, it can do the instant part instead. Its Litter-Robot integration gets the robot's
updates in real time, and the Home Assistant app on your iPhone receives push notifications for free. Meowstache stays
your app for history, insights and settings; Home Assistant just sends the "right now" messages.

## What you need

- Home Assistant with the **Litter-Robot** integration (**Settings › Devices & services › Add integration ›
  Litter-Robot**, signed in with your Whisker account).
- The **Home Assistant app** on your iPhone, signed in to your Home Assistant, with notifications allowed. It appears
  as a notify action named after the phone, like `notify.mobile_app_my_iphone` (**Developer tools › Actions**, type
  `notify.mobile_app` to find yours).
- Outside your home network, the app needs an address it can reach (Home Assistant Cloud, or your own HTTPS address)
  for tapping a notification to open; the notifications themselves arrive anyway.

## The automations

In Home Assistant: **Settings › Automations & scenes › Create automation › ⋮ › Edit in YAML**, paste one, and change
the entity ids to yours. They're shown here for a robot named Robbie and cats named Mochi and Luna; Home Assistant
names the entities after your robot and pets (look under the Litter-Robot device).

| Entity | What it is |
|---|---|
| `sensor.robbie_pet_weight` | the last weight the robot measured (changes at each visit) |
| `sensor.mochi_weight`, `sensor.luna_weight` | each cat's weight, from Whisker |
| `sensor.robbie_status_code` | the robot's status: `cd` cat detected, `cst` cat sensor timing, `ccp` cleaning, `rdy` ready, `dfs` drawer full… |
| `sensor.robbie_waste_drawer` | how full the waste drawer is, in % |

### A cat visited (the moment it's weighed)

The robot weighs the cat during the visit; this names the cat whose weight is closest.

```yaml
alias: Litter-Robot visit
triggers:
  - trigger: state
    entity_id: sensor.robbie_pet_weight
conditions:
  - condition: template
    value_template: "{{ trigger.to_state.state not in ['unknown', 'unavailable'] }}"
actions:
  - variables:
      kg: >
        {% set w = trigger.to_state.state | float(0) %}
        {{ (w * 0.4536) if trigger.to_state.attributes.unit_of_measurement == 'lb' else w }}
      cat: >
        {% set ns = namespace(name='A cat', d=1.0) %}
        {% for name, entity in [('Mochi', 'sensor.mochi_weight'), ('Luna', 'sensor.luna_weight')] %}
          {% set d = (states(entity) | float(0) - kg | float) | abs %}
          {% if d < ns.d %}{% set ns.name = name %}{% set ns.d = d %}{% endif %}
        {% endfor %}
        {{ ns.name }}
  - action: notify.mobile_app_my_iphone
    data:
      title: "{{ cat }} visited Robbie"
      message: "{{ '%.2f' | format(kg | float) }} kg · at {{ now().strftime('%H:%M') }}"
mode: queued
```

A cat more than 1 kg away from all of them is "A cat". With cats of similar weight, the guess can be wrong (Meowstache
has the same limit).

### Cleaning cycle finished

```yaml
alias: Litter-Robot cycle complete
triggers:
  - trigger: state
    entity_id: sensor.robbie_status_code
    from: ccp
    to: [ccc, rdy]
actions:
  - action: notify.mobile_app_my_iphone
    data:
      title: Clean cycle complete
      message: Robbie finished a clean cycle.
```

### Waste drawer full, or a fault

```yaml
alias: Litter-Robot needs attention
triggers:
  - trigger: state
    entity_id: sensor.robbie_status_code
    to: [dfs, df2, br, csf, dhf, dpf, hpf, otf, pd, scf, sdf, spf, offline]
    for: "00:01:00" # ignore blips that clear by themselves
actions:
  - action: notify.mobile_app_my_iphone
    data:
      title: Robbie needs attention
      message: >
        {{ {'dfs': 'The waste drawer is full.', 'df2': 'The waste drawer is almost full (1 cycle left).',
            'sdf': 'The waste drawer is full.', 'br': 'The bonnet is off.', 'offline': 'Robbie is offline.',
            'pd': 'Pinch detected: cleaning paused.', 'spf': 'Pinch detected: cleaning paused.'}
           .get(trigger.to_state.state, 'Fault: ' ~ trigger.to_state.state ~ '. Check the robot.') }}
```

## Good to know

- Turn off the same alerts in Meowstache (**Account › Notifications**) if you'd rather not get them twice when the app
  is open.
- **Local mode** (robots on the ESPHome firmware): the robot's entities come from ESPHome instead. Use its
  `sensor.<robot>_status` (states like `Cat detected`, `Clean cycle in progress`, `Ready`) and the cat tracker's weight
  sensors; the same automations work with those names and states.
- The Home Assistant app's push notifications go through Home Assistant's free relay, which allows plenty per day for
  this.
