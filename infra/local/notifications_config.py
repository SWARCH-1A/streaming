"""Add Notifications certificates and secrets to an existing local installation without rotating others."""
import os
import re
import secrets


def certificate(state, run, name, aliases):
    if (state / (name + ".crt")).exists():
        return
    run(["openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", name + ".key", "-out", name + ".csr", "-subj", "/CN=" + name], cwd=state)
    extension = state / (name + ".ext")
    names = ",".join("DNS:" + alias for alias in (name, *aliases))
    extension.write_text("basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n"
                         "subjectAltName=" + names + ",DNS:localhost,IP:127.0.0.1\n")
    run(["openssl", "x509", "-req", "-in", name + ".csr", "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial",
         "-out", name + ".crt", "-days", "90", "-sha256", "-extfile", str(extension)], cwd=state)
    for suffix in (".crt", ".key"):
        os.chmod(state / (name + suffix), 0o644)  # Individual read-only bind mounts; host parent is 0700.


def initialize(env, state, run):
    keys = ("NOTIFICATIONS_DB_PASSWORD", "NOTIFICATIONS_CURSOR_SECRET", "CORE_NOTIFICATIONS_SERVICE_TOKEN",
            "CORE_RECORDING_SETTINGS_SERVICE_TOKEN")
    for key in keys:
        env.setdefault(key, secrets.token_urlsafe(36))
        if not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", env[key]):
            raise RuntimeError(key + " must be a private URL-safe token of at least 32 characters")
    certificate(state, run, "notifications", ())
    certificate(state, run, "notifications-db", ())
    certificate(state, run, "mailpit", ())
    path = state / "environment.env"
    values = dict(line.split("=", 1) for line in path.read_text().splitlines())
    values.update({key: env[key] for key in keys})
    temporary = path.with_suffix(".notifications.tmp")
    temporary.write_text("".join(f"{key}={value}\n" for key, value in sorted(values.items())))
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)
    return env
