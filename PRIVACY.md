# Meowstache privacy

- Your password goes straight to the Litter-Robot login server over HTTPS and is never stored.
- A login token is kept on your phone, encrypted with a key in the phone's secure hardware (Android Keystore), and never backed up.
- Your robot and pet history is stored only on your phone, and in Android's own backup if you use it.
- The app only talks to the Litter-Robot servers. No analytics, no ads, no other servers. The developer never receives any of your data.

**Local mode** (robots on the community ESPHome firmware, read through your Home Assistant): the app talks only to the Home Assistant address you enter, never to the Litter-Robot servers. Its access token is kept like the login token above: encrypted on your phone, never backed up.

Questions: meowstache.app@gmail.com.

Your data on the Litter-Robot servers is covered by the [manufacturer's privacy policy](https://www.litter-robot.com/legal.html#privacypolicy).
