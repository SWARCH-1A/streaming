"""Opt-in LAN HTTPS for the local installation; preserve the CA and all service secrets."""
import ipaddress
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile


def private_address(value):
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError:
        raise RuntimeError("LAN address must be a private IPv4 address") from None
    if not any(address in ipaddress.ip_network(network) for network in
               ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
        raise RuntimeError("LAN address must be a private IPv4 address")
    return str(address)


def public_host(value):
    value = value.lower()
    try:
        return private_address(value)
    except RuntimeError:
        if len(value) <= 253 and value.endswith(".local") and all(
            re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
            for label in value.split(".")
        ):
            return value
    raise RuntimeError("LAN host must be a .local DNS name or private IPv4 address, without a scheme or port")


def validate(env):
    host = env.get("LOCAL_PUBLIC_HOST", "localhost")
    bind = env.get("LOCAL_HTTPS_BIND", "127.0.0.1")
    address = env.get("LOCAL_LAN_IP", "127.0.0.1")
    if host == "localhost":
        if bind != "127.0.0.1" or address != "127.0.0.1":
            raise RuntimeError("Use the lan action to configure LAN access")
    elif public_host(host) != host or bind != "0.0.0.0" or private_address(address) != address:
        raise RuntimeError("Invalid LAN configuration; run the lan action again")


def detect_address():
    # UDP connect selects the default route without sending application data.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.connect(("192.0.2.1", 9))
        return private_address(probe.getsockname()[0])


def detect_host():
    if shutil.which("scutil"):
        name = subprocess.check_output(["scutil", "--get", "LocalHostName"], text=True).strip()
        return public_host(name + ".local")
    raise RuntimeError("Specify --host with a reachable .local name, or the private IPv4 address")


def configure(env, state, run, *, host=None, address=None, loopback=False):
    state = Path(state)
    host = "localhost" if loopback else public_host(host) if host else detect_host()
    address = "127.0.0.1" if loopback else private_address(address) if address else detect_address()
    bind = "127.0.0.1" if loopback else "0.0.0.0"
    if host != "localhost" and not host.endswith(".local") and host != address:
        raise RuntimeError("An IP-based LAN host must match --address")
    if not loopback:
        try:
            if ipaddress.ip_address(address) in ipaddress.ip_network(env["LOCAL_SUBNET"]):
                raise RuntimeError("LAN overlaps the Docker subnet; choose a different Docker subnet before enabling LAN")
        except KeyError:
            raise RuntimeError("Initialize the local installation first") from None
    # Reissue only the public-entry leaf certificate, retaining its internal web/proxy SANs.
    # Keep the CA and private key so existing browser trust and unrelated services remain valid.
    names = ["DNS:web", "DNS:proxy", "DNS:localhost", "IP:127.0.0.1"]
    if host != "localhost":
        names += ["IP:" + host if host == address else "DNS:" + host, "IP:" + address]
    with tempfile.TemporaryDirectory(prefix=".network-", dir=state) as temporary:
        temporary = Path(temporary)
        extension = temporary / "web.ext"
        extension.write_text("basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\n"
                             "extendedKeyUsage=serverAuth\nsubjectAltName=" + ",".join(dict.fromkeys(names)) + "\n")
        run(["openssl", "req", "-new", "-key", str(state / "web.key"), "-out", str(temporary / "web.csr"), "-subj", "/CN=web"])
        run(["openssl", "x509", "-req", "-in", str(temporary / "web.csr"), "-CA", str(state / "ca.crt"),
             "-CAkey", str(state / "ca.key"), "-set_serial", "0x" + os.urandom(16).hex(),
             "-out", str(temporary / "web.crt"), "-days", "90", "-sha256", "-extfile", str(extension)])
        run(["openssl", "x509", "-in", str(state / "ca.crt"), "-outform", "DER", "-out", str(temporary / "ca.cer")])
        values = dict(line.split("=", 1) for line in (state / "environment.env").read_text().splitlines())
        values.update(LOCAL_PUBLIC_HOST=host, LOCAL_LAN_IP=address, LOCAL_HTTPS_BIND=bind,
                      WEB_ORIGIN=f"https://{host}:{env['P1_HTTPS_PORT']}")
        validate(values)
        path = temporary / "environment.env"
        path.write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())))
        path.chmod(0o600)
        (temporary / "web.crt").chmod(0o644)
        (temporary / "ca.cer").chmod(0o644)
        os.replace(temporary / "ca.cer", state / "ca.cer")
        os.replace(temporary / "web.crt", state / "web.crt")
        os.replace(path, state / "environment.env")
    print(f"HTTPS origin: {values['WEB_ORIGIN']}; bind: {bind}; RTMPS and Mailpit remain loopback-only")
    if not loopback:
        print("Install only ca.crt on the iPhone and enable full root trust. Use this origin in the app and browser.")
    return {**env, **values}
