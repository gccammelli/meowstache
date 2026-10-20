#!/usr/bin/env python3
"""Create the private development signing set for the isolated test app and WDA through the App Store Connect API.

Registers the one USB iPhone, the two test bundle IDs, an Apple Development certificate and one development
profile per bundle. Everything lands in IOS_PRIVATE_DIR (outside the repository); nothing personal is printed.
"""
import argparse
import asyncio
import base64
import json
import os
from pathlib import Path
import time

import requests
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from cryptography.x509.oid import NameOID

from install import TEST_APP, WDA

API = "https://api.appstoreconnect.apple.com/v1"
APPS = {
    "meowstache": (TEST_APP, "Meowstache"),
    "pandy": ("io.github.gccammelli.pandy.testbed", "Pandy"),
    "liberpet": ("io.github.gccammelli.liberpet.testbed", "LiberPet"),
}


def token(key_id, issuer, key_pem):
    def b64(data):
        return base64.urlsafe_b64encode(data).rstrip(b"=").decode()
    header = b64(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}).encode())
    now = int(time.time())
    claims = b64(json.dumps({"iss": issuer, "iat": now, "exp": now + 1200, "aud": "appstoreconnect-v1"}).encode())
    key = serialization.load_pem_private_key(key_pem, None)
    r, s = decode_dss_signature(key.sign(f"{header}.{claims}".encode(), ec.ECDSA(hashes.SHA256())))
    return f"{header}.{claims}.{b64(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


class Api:
    def __init__(self, bearer):
        self.session = requests.Session()
        self.session.headers["Authorization"] = f"Bearer {bearer}"

    def call(self, method, path, body=None, **params):
        response = self.session.request(method, API + path, json=body, params=params, timeout=30)
        if response.status_code >= 400:
            # Apple's error text can quote names or identifiers; keep only the codes.
            codes = [e.get("code") for e in response.json().get("errors", [])] if response.content else []
            raise RuntimeError(f"{method} {path.split('?')[0]} -> HTTP {response.status_code} {codes}")
        return response.json() if response.content else {}


def usb_udid():
    from pymobiledevice3.usbmux import list_devices
    devices = [d for d in asyncio.run(list_devices()) if d.is_usb]
    if len(devices) != 1:
        raise ValueError("Connect exactly one USB test iPhone")
    return devices[0].serial


def ensure_device(api, udid):
    found = api.call("GET", "/devices", **{"filter[udid]": udid})["data"]
    if found:
        return found[0]["id"]
    return api.call("POST", "/devices", {"data": {"type": "devices", "attributes": {
        "name": "Meowstache test iPhone", "udid": udid, "platform": "IOS"}}})["data"]["id"]


def ensure_bundle(api, identifier, name):
    for bundle in api.call("GET", "/bundleIds", **{"filter[identifier]": identifier})["data"]:
        if bundle["attributes"]["identifier"] == identifier:  # the filter also matches longer identifiers
            return bundle["id"]
    return api.call("POST", "/bundleIds", {"data": {"type": "bundleIds", "attributes": {
        "identifier": identifier, "name": name, "platform": "IOS"}}})["data"]["id"]


def ensure_certificate(api, private):
    key_path, cert_path = private / "dev-key.pem", private / "dev-cert.pem"
    if key_path.exists() and cert_path.exists():
        cert = x509.load_pem_x509_certificate(cert_path.read_bytes())
        serial = format(cert.serial_number, "X")
        for remote in api.call("GET", "/certificates", **{"filter[certificateType]": "DEVELOPMENT"})["data"]:
            if remote["attributes"]["serialNumber"].upper().lstrip("0") == serial.lstrip("0"):
                return remote["id"]
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    csr = x509.CertificateSigningRequestBuilder().subject_name(
        x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Meowstache testbed")])).sign(key, hashes.SHA256())
    created = api.call("POST", "/certificates", {"data": {"type": "certificates", "attributes": {
        "certificateType": "DEVELOPMENT",
        "csrContent": csr.public_bytes(serialization.Encoding.PEM).decode()}}})["data"]
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                           serialization.NoEncryption()))
    key_path.chmod(0o600)
    der = base64.b64decode(created["attributes"]["certificateContent"])
    cert_path.write_bytes(x509.load_der_x509_certificate(der).public_bytes(serialization.Encoding.PEM))
    return created["id"]


def make_profile(api, private, identifier, bundle_id, cert_id, device_id, prefix):
    name = prefix + identifier.rsplit(".", 1)[-1]
    # Only our own named profiles are replaced, so a new device or certificate is always included.
    for old in api.call("GET", "/profiles", **{"filter[name]": name})["data"]:
        if old["attributes"]["name"] == name:
            api.call("DELETE", f"/profiles/{old['id']}")
    created = api.call("POST", "/profiles", {"data": {"type": "profiles",
        "attributes": {"name": name, "profileType": "IOS_APP_DEVELOPMENT"},
        "relationships": {
            "bundleId": {"data": {"type": "bundleIds", "id": bundle_id}},
            "certificates": {"data": [{"type": "certificates", "id": cert_id}]},
            "devices": {"data": [{"type": "devices", "id": device_id}]}}}})["data"]
    out = private / f"{identifier}.mobileprovision"
    out.write_bytes(base64.b64decode(created["attributes"]["profileContent"]))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", choices=APPS, default="meowstache")
    args = parser.parse_args()
    private_dir = os.environ.get("IOS_PRIVATE_DIR")
    if not private_dir:
        raise SystemExit("Set IOS_PRIVATE_DIR to the private Apple signing folder")
    private = Path(private_dir).expanduser()
    private.mkdir(mode=0o700, parents=True, exist_ok=True)
    key_file = Path(os.environ["ASC_KEY"]).expanduser()
    api = Api(token(os.environ["ASC_KEY_ID"], os.environ["ASC_ISSUER_ID"], key_file.read_bytes()))
    device_id = ensure_device(api, usb_udid())
    cert_id = ensure_certificate(api, private)
    bundle, display = APPS[args.app]
    identifiers = [(bundle, f"{display} testbed")]
    if args.app == "meowstache":
        identifiers.append((WDA, "Meowstache testbed WDA"))
    for identifier, name in identifiers:
        make_profile(api, private, identifier, ensure_bundle(api, identifier, name), cert_id, device_id,
                     f"{display} testbed ")
    print("Device, requested bundle profiles and development certificate are ready in the private directory")


if __name__ == "__main__":
    main()
