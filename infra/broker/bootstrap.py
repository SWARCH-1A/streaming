"""Add broker secrets/certificates without rotating an existing local installation."""
from pathlib import Path
import json
import os
import runpy
import re
import secrets


def initialize(env, state, run):
    root = Path(__file__).resolve().parents[2]
    topology = runpy.run_path(str(root / "infra/broker/topology.py"))
    roles = ("streaming", "core", "chat", "vod", "notifications", "operator")
    for role in roles:
        env.setdefault("BROKER_" + role.upper() + "_PASSWORD", secrets.token_urlsafe(36))
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", env["BROKER_" + role.upper() + "_PASSWORD"]):
            raise RuntimeError("Broker passwords must be URL-safe tokens of at least 32 characters")
    if not (state / "broker.crt").exists():
        run(["openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", "broker.key",
             "-out", "broker.csr", "-subj", "/CN=broker"], cwd=state)
        extension = state / "broker.ext"
        extension.write_text("basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:broker,DNS:localhost,IP:127.0.0.1\n")
        run(["openssl", "x509", "-req", "-in", "broker.csr", "-CA", "ca.crt", "-CAkey", "ca.key",
             "-CAcreateserial", "-out", "broker.crt", "-days", "90", "-sha256", "-extfile", str(extension)], cwd=state)
    passwords = {role: env["BROKER_" + role.upper() + "_PASSWORD"] for role in roles}
    definitions = state / "broker-definitions.json"
    definitions.write_text(json.dumps(topology["definitions"](passwords), indent=2) + "\n")
    for name in ("broker.crt", "broker.key", "broker-definitions.json"):
        os.chmod(state / name, 0o644)  # Individual read-only bind mounts; host parent is 0700.
    env_file = state / "environment.env"
    values = dict(line.split("=", 1) for line in env_file.read_text().splitlines())
    values.update({"BROKER_" + role.upper() + "_PASSWORD": env["BROKER_" + role.upper() + "_PASSWORD"] for role in roles})
    temporary = env_file.with_suffix(".broker.tmp")
    temporary.write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())))
    os.chmod(temporary, 0o600)
    os.replace(temporary, env_file)
    return env
