#!/usr/bin/env python3
"""
Meowstache Ring clips: saves every Ring recording of a litter-box visit into Home Assistant's media folder, named by
the moment it started recording (UTC), for Meowstache's visit videos.

Why: Ring splits a long visit into several recordings, and Home Assistant's Ring integration only shows the latest one,
so an automation on it misses the earlier parts (often the cat walking in). This asks Ring's own history for every
recording instead, and has Home Assistant download them (its Downloader integration), so nothing needs write access
to Home Assistant's disk.

Gentle on Ring: it only asks Ring while a visit is recent (the robot's status in Home Assistant said a cat was there
in the last 20 minutes), at most every 90 s, plus one slow check every 30 minutes; a budget caps it at 40 Ring
requests an hour whatever happens.

    python3 meowstache_ring_clips.py --config ring-clips.json --login   # once: Ring sign-in (password, 2FA code)
    python3 meowstache_ring_clips.py --config ring-clips.json           # the service
See README.md next to this file.
"""
import argparse
import asyncio
import getpass
import json
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

USER_AGENT = "Meowstache-ring-clips/1.0"
DEFAULTS = {
    "camera": None,              # the Ring camera's name, as in the Ring app; None = the only camera
    "ring_token_file": "ring-token.json",
    "ha_url": None,              # e.g. http://homeassistant.local:8123
    "ha_token_file": None,       # a long-lived access token
    "subdir": "meowstache",      # Downloader saves into <its folder>/<subdir>, e.g. /media/meowstache
    "robot_status_entity": "sensor.litter_robot_4_status_code",  # None = save every recording of the camera
    "cat_states": ["cd", "cst"],  # local mode (ESPHome): ["Cat detected", "Waiting to cycle"]
    "margin_s": 90,              # recordings starting this long before or after a cat was there count too
    "state_file": "ring-clips-state.json",
}
RECENT = timedelta(minutes=20)


def log(*a):
    print(datetime.now().strftime("%H:%M:%S"), *a, flush=True)


def clip_name(start: datetime) -> str:
    """Meowstache's clip name: the UTC start time, e.g. 2026-10-16T22-32-41Z.mp4."""
    return start.astimezone(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ.mp4")


def cat_periods(history: list[dict], cat_states: list[str], now: datetime) -> list[tuple[datetime, datetime]]:
    """Stretches of time the robot's status said a cat was there, from Home Assistant's state history."""
    out, start = [], None
    for s in history:
        t = datetime.fromisoformat(s["last_changed"])
        if s["state"] in cat_states and start is None:
            start = t
        elif s["state"] not in cat_states and start is not None:
            out.append((start, t)); start = None
    if start is not None:
        out.append((start, now))
    return out


def wanted(rec_start: datetime, rec_len_s: float, periods, margin_s: float) -> bool:
    """A recording belongs to a visit if it overlaps one, give or take the margin."""
    m = timedelta(seconds=margin_s)
    rec_end = rec_start + timedelta(seconds=rec_len_s)
    return any(rec_start <= e + m and rec_end >= s - m for s, e in periods)


class Budget:
    """At most [per_hour] Ring requests in any hour."""
    def __init__(self, per_hour=40):
        self.per_hour, self.times = per_hour, []

    def take(self) -> bool:
        now = time.time()
        self.times = [t for t in self.times if now - t < 3600]
        if len(self.times) >= self.per_hour:
            return False
        self.times.append(now)
        return True


class HomeAssistant:
    def __init__(self, url, token):
        self.url, self.token = url.rstrip("/"), token

    def _req(self, path, body=None):
        req = urllib.request.Request(self.url + path, method="POST" if body is not None else "GET",
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read() or b"null")

    def status_history(self, entity, since: datetime):
        start = since.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        end = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        r = self._req(f"/api/history/period/{start}?end_time={end}&filter_entity_id={entity}&minimal_response")
        return r[0] if r else []

    def state(self, entity):
        return self._req(f"/api/states/{entity}")["state"]

    def download(self, url, subdir, filename):
        self._req("/api/services/downloader/download_file", {"url": url, "subdir": subdir, "filename": filename, "overwrite": False})


def token_store(path: Path):
    def save(token):
        path.write_text(json.dumps(token)); path.chmod(0o600)
    return save


async def login(cfg):
    from ring_doorbell import Auth, Requires2FAError
    path = Path(cfg["ring_token_file"])
    auth = Auth(USER_AGENT, None, token_store(path))
    email = input("Ring email: ").strip()
    password = getpass.getpass("Ring password: ")
    try:
        await auth.async_fetch_token(email, password)
    except Requires2FAError:
        await auth.async_fetch_token(email, password, input("Ring 2FA code (sent by Ring): ").strip())
    await auth.async_close()
    print(f"Signed in; token saved to {path} (keep it private: it gives access to your Ring account).")


async def run(cfg):
    from ring_doorbell import Auth, Ring
    path = Path(cfg["ring_token_file"])
    if not path.exists():
        sys.exit(f"No Ring token at {path}: run with --login first")
    auth = Auth(USER_AGENT, json.loads(path.read_text()), token_store(path))
    ring = Ring(auth)
    budget = Budget()
    if not budget.take():
        sys.exit("budget")
    await ring.async_create_session()
    await ring.async_update_data()
    cams = ring.video_devices()
    cam = next((c for c in cams if c.name == cfg["camera"]), None) if cfg["camera"] else (cams[0] if len(cams) == 1 else None)
    if cam is None:
        sys.exit(f"Camera {cfg['camera']!r} not found; this account's cameras: {[c.name for c in cams]}")
    ha = HomeAssistant(cfg["ha_url"], Path(cfg["ha_token_file"]).read_text().strip())
    state_path = Path(cfg["state_file"])
    saved = set(json.loads(state_path.read_text())) if state_path.exists() else set()
    entity, cats = cfg["robot_status_entity"], cfg["cat_states"]
    log(f"watching {cam.name!r}; {len(saved)} recordings already saved")
    last_cat = datetime.min.replace(tzinfo=timezone.utc)
    last_check, first = 0.0, True
    while True:
        now = datetime.now(timezone.utc)
        try:
            if entity and ha.state(entity) in cats:
                last_cat = now
        except Exception as e:  # Home Assistant restarting: try again next round
            log("robot status unavailable:", e)
        busy = now - last_cat < RECENT
        due = time.time() - last_check >= (90 if busy else 1800)
        if (first or due) and budget.take():
            last_check, first = time.time(), False
            try:
                n = await check(cam, ha, cfg, saved, now, budget)
                if n:
                    tmp = state_path.with_suffix(".tmp")  # written whole, then swapped in
                    tmp.write_text(json.dumps(sorted(saved, key=int)[-2000:])); tmp.replace(state_path)
            except Exception as e:  # Ring or the network hiccupped: try again in 90 s, not at the next slow check
                log("check failed:", e)
                last_check = time.time() - 1800 + 90
        await asyncio.sleep(15)


async def check(cam, ha, cfg, saved, now, budget) -> int:
    """Saves the visit recordings not saved yet; returns how many."""
    history = await cam.async_history(limit=30, retry=1)
    periods = None
    if cfg["robot_status_entity"]:
        periods = cat_periods(ha.status_history(cfg["robot_status_entity"], now - timedelta(hours=6)), cfg["cat_states"], now)
    n = 0
    for rec in history:
        rid = str(rec["id"])
        if rid in saved or (rec.get("recording") or {}).get("status") != "ready":
            continue
        start = rec["created_at"] if isinstance(rec["created_at"], datetime) else datetime.fromisoformat(rec["created_at"].replace("Z", "+00:00"))
        if now - start > timedelta(hours=6):
            saved.add(rid); continue  # too old to matter (and keeps the first run from saving the whole history)
        if periods is not None and not wanted(start, float(rec.get("duration") or 60), periods, cfg["margin_s"]):
            continue  # might still become part of a visit that's under way: looked at again next time
        if not budget.take():
            break  # out of Ring requests this hour: the rest next time
        url = await cam.async_recording_url(rec["id"])
        if not url:
            continue
        ha.download(url, cfg["subdir"], clip_name(start))
        saved.add(rid); n += 1
        log("saved", clip_name(start), f"({rec.get('kind')}, {rec.get('duration')} s)")
    return n


def main():
    ap = argparse.ArgumentParser(description=__doc__.strip().split("\n\n")[0])
    ap.add_argument("--config", required=True)
    ap.add_argument("--login", action="store_true", help="sign in to Ring once and save the token")
    a = ap.parse_args()
    cfg = {**DEFAULTS, **json.loads(Path(a.config).read_text())}
    if a.login:
        asyncio.run(login(cfg)); return
    if not cfg["ha_url"] or not cfg["ha_token_file"]:
        sys.exit("ha_url and ha_token_file are required")
    asyncio.run(run(cfg))


if __name__ == "__main__":
    main()
