"""Sets up a fresh Home Assistant the way a user of the ESPHome Litter-Robot firmware would:
onboarding, a long-lived token for Meowstache, both mock robots added via the ESPHome integration,
the hidden cat entities enabled, cats renamed and given starting weights (see the firmware's User Guide).

    python setup_ha.py http://localhost:8123   → prints the token
"""
import asyncio, json, sys
import aiohttp

URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8123"
CID = URL + "/"
ROBOTS = [  # port, robot name, cats in tracker order (name, lb)
    (6053, "Robbie", [("Mochi", 11.4), ("Luna", 8.6)]),
    (6054, "Bobbie", [("Luna", 8.6), ("Pixel", 10.1)]),
]


async def main():
    async with aiohttp.ClientSession() as s:
        r = await s.post(f"{URL}/api/onboarding/users", json={"client_id": CID, "name": "Mock", "username": "mock", "password": "mockmockmock", "language": "en"})
        fresh = r.status == 200
        if fresh:
            code = (await r.json())["auth_code"]
        else:  # already onboarded: log in instead
            r = await s.post(f"{URL}/auth/login_flow", json={"client_id": CID, "handler": ["homeassistant", None], "redirect_uri": CID})
            r = await s.post(f"{URL}/auth/login_flow/{(await r.json())['flow_id']}", json={"username": "mock", "password": "mockmockmock", "client_id": CID})
            code = (await r.json())["result"]
        r = await s.post(f"{URL}/auth/token", data={"grant_type": "authorization_code", "code": code, "client_id": CID})
        h = {"Authorization": "Bearer " + (await r.json())["access_token"]}
        for step, body in [("core_config", {}), ("analytics", {}), ("integration", {"client_id": CID, "redirect_uri": CID})]:
            await s.post(f"{URL}/api/onboarding/{step}", json=body, headers=h)

        for port, _, _ in ROBOTS if fresh else []:
            r = await s.post(f"{URL}/api/config/config_entries/flow", json={"handler": "esphome"}, headers=h)
            flow = await r.json()
            r = await s.post(f"{URL}/api/config/config_entries/flow/{flow['flow_id']}", json={"host": "127.0.0.1", "port": port}, headers=h)
            print("esphome", port, (await r.json()).get("type"), file=sys.stderr)

        ws = await s.ws_connect(f"{URL}/api/websocket")
        await ws.receive_json()
        await ws.send_json({"type": "auth", "access_token": h["Authorization"][7:]})
        await ws.receive_json()
        n = 0

        async def call(msg):
            nonlocal n
            n += 1
            await ws.send_json({"id": n, **msg})
            while True:
                m = await ws.receive_json()
                if m.get("id") == n:
                    if not m.get("success"):
                        raise RuntimeError(m)
                    return m.get("result")

        await asyncio.sleep(5)
        devices = {d["id"]: d for d in await call({"type": "config/device_registry/list"})}
        entities = await call({"type": "config/entity_registry/list"})
        set_weight = {}
        for port, robot, cats in ROBOTS:
            dev = next(d for d in devices.values() if d["model"] == "Litter Robot 4" and any(
                c[0] == "mac" and c[1].endswith(f":0{'a' if port == 6053 else 'b'}") for c in d["connections"]))
            await call({"type": "config/device_registry/update", "device_id": dev["id"], "name_by_user": robot})
            for e in (e for e in entities if e["device_id"] == dev["id"]):
                upd = {}
                if e["disabled_by"]:
                    upd["disabled_by"] = None
                for i, (cat, lb) in enumerate(cats, 1):  # "Rename the numbered cat's entities with the desired cat name"
                    if e["original_name"] and e["original_name"].startswith(f"Cat {i} "):
                        upd["name"] = e["original_name"].replace(f"Cat {i}", cat)
                    if e["original_name"] == f"Set Cat {i} Weight":
                        set_weight[e["entity_id"]] = lb
                if upd:
                    await call({"type": "config/entity_registry/update", "entity_id": e["entity_id"], **upd})
        for entry in await call({"type": "config_entries/get", "domain": "esphome"}):
            await s.post(f"{URL}/api/config/config_entries/entry/{entry['entry_id']}/reload", headers=h)
        await asyncio.sleep(8)

        for eid, lb in set_weight.items():  # retried: the entities come back a few seconds after the reload
            for _ in range(10):
                await s.post(f"{URL}/api/services/number/set_value", json={"entity_id": eid, "value": lb}, headers=h)
                if (await (await s.get(f"{URL}/api/states/{eid}", headers=h)).json())["state"] not in ("unknown", "unavailable"):
                    break
                await asyncio.sleep(2)

        token = await call({"type": "auth/long_lived_access_token", "client_name": "Meowstache", "lifespan": 3650})
        print(token)


asyncio.run(main())
