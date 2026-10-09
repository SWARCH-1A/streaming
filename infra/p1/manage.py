"""Own persistent academic Compose project. Generated secrets never go to stdout.

Python 3.12+, OpenSSL and Docker Compose >=2.24.4 on macOS/Linux (Windows: WSL).
"""
from __future__ import annotations

import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
STATE = HERE / ".state"
PROJECT = "streaming-p1"


def run(args, **kwargs):
    # Capture certificate/build diagnostics: command lines may contain configuration secrets.
    result = subprocess.run(args, text=True, capture_output=True, **kwargs)
    if result.returncode:
        if STATE.exists():
            diagnostic = STATE / "diagnostics.log"
            with open(diagnostic, "a") as out: out.write(result.stdout + result.stderr)
            os.chmod(diagnostic, 0o600)
        raise RuntimeError(f"{Path(args[0]).name} {args[1]} failed (exit {result.returncode}); inspect configuration locally")
    return result.stdout


def render_proxy():
    source = (ROOT / "infra/reverse-proxy/Caddyfile").read_text()
    if source.count(":3000 {") != 1 or source.count("      import proxy_headers") != 6:
        raise RuntimeError("Review P1 TLS renderer after reverse-proxy structure changes")
    source = source.replace(":3000 {", "https://localhost:3443 {\n  tls /run/secrets/web.crt /run/secrets/web.key")
    source = source.replace("      transport http {\n        response_header_timeout 10s\n      }\n", "")
    for name in ("CORE", "STREAMING", "CHAT", "HLS"):
        source = source.replace("{$" + name + "_UPSTREAM:http:" , "{$" + name + "_UPSTREAM:https:")
    source = source.replace("      import proxy_headers", """      import proxy_headers
      transport http {
        tls_trust_pool file /run/secrets/ca.crt
        response_header_timeout 10s
      }""")
    # Host-only session cookies remain on the public origin; private credentials never enter logs.
    return source


def initialize():
    if STATE.exists():
        env = environment()
        print(f"Existing {PROJECT} state retained: {STATE}")
        return env
    HERE.mkdir(exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".p1-init-", dir=HERE))
    os.chmod(temporary, 0o700)
    try:
        def openssl(*args, **kwargs): return run(["openssl", *args], cwd=temporary, **kwargs)
        openssl("req","-x509","-newkey","rsa:2048","-nodes","-keyout","ca.key","-out","ca.crt","-days","30","-sha256","-subj","/CN=STREAMING P1 local CA")
        names = ("core","streaming","chat","mediamtx","postgres","db","chat-redis","web")
        for name in names:
            openssl("req","-newkey","rsa:2048","-nodes","-keyout",name+".key","-out",name+".csr","-subj","/CN="+name)
            extension = temporary / (name+".ext")
            extension.write_text("basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:"+name+",DNS:localhost,IP:127.0.0.1\n")
            openssl("x509","-req","-in",name+".csr","-CA","ca.crt","-CAkey","ca.key","-CAcreateserial","-out",name+".crt","-days","14","-sha256","-extfile",str(extension))
        keys = ("CORE_DB_PASSWORD","STREAMING_DB_PASSWORD","CORE_RATE_LIMIT_HMAC_SECRET","CORE_STREAMING_SERVICE_TOKEN",
                "CORE_STREAMING_CATALOG_SERVICE_TOKEN","CORE_STREAMING_CONSUMER_TOKEN","CHAT_CORE_SERVICE_TOKEN",
                "CHAT_SESSION_EVENTS_TOKEN","MEDIA_AUTH_TOKEN","MEDIA_HLS_SECRET","MEDIA_CONTROL_PASSWORD",
                "MEDIA_DATABASE_PASSWORD","STREAMING_MEDIA_ADAPTER_SERVICE_TOKEN","STREAMING_VIEWER_LEASE_HMAC_KEY",
                "CORE_INTERNAL_TLS_KEYSTORE_PASSWORD")
        env = {key: secrets.token_urlsafe(36) for key in keys}
        openssl("pkcs12","-export","-in","core.crt","-inkey","core.key","-certfile","ca.crt","-out","core-internal.p12",
                "-passout","env:P1_KEYSTORE_PASSWORD",env={**os.environ,"P1_KEYSTORE_PASSWORD":env["CORE_INTERNAL_TLS_KEYSTORE_PASSWORD"]})
        # Preserve JDK public roots for the optional HTTPS S3 provider; append our local CA.
        run(["docker","run","--rm","--network","none","--user",f"{os.getuid()}:{os.getgid()}","-v",str(temporary)+":/state","eclipse-temurin:25-jdk",
             "sh","-c",'keytool -importkeystore -noprompt -srckeystore "$JAVA_HOME/lib/security/cacerts" -srcstorepass changeit -destkeystore /state/truststore.p12 -deststoretype PKCS12 -deststorepass changeit'])
        run(["docker","run","--rm","--network","none","--user",f"{os.getuid()}:{os.getgid()}","-v",str(temporary)+":/state","eclipse-temurin:25-jdk",
             "keytool","-importcert","-noprompt","-alias","p1-ca","-file","/state/ca.crt","-keystore","/state/truststore.p12","-storetype","PKCS12","-storepass","changeit"])
        fingerprint = openssl("x509","-in","streaming.crt","-noout","-fingerprint","-sha256").strip().split("=")[-1].replace(":","").lower()
        https_port=port("P1_HTTPS_PORT","3443")
        rtmps_port=port("P1_RTMPS_PORT","11936")
        env.update({"P1_STATE":str(STATE),"P1_HTTPS_PORT":https_port,"P1_RTMPS_PORT":rtmps_port,"WEB_ORIGIN":"https://localhost:"+https_port,"CORE_INTERNAL_TLS_KEYSTORE":str(STATE / "core-internal.p12"),
                    "STREAMING_CERT_FINGERPRINT":fingerprint})
        (temporary / "Caddyfile").write_text(render_proxy())
        (temporary / "environment.env").write_text("".join(f"{key}={value}\n" for key,value in sorted(env.items())))
        (temporary / "owner.json").write_text(json.dumps({"project":PROJECT,"root":str(ROOT)},indent=2)+"\n")
        # Only individual files are bind-mounted. Their host parent stays 0700; runtime UID can read them.
        for path in temporary.iterdir(): os.chmod(path, 0o644 if path.suffix in (".crt",".key",".p12") else 0o600)
        os.chmod(temporary / "ca.key", 0o600) # Never mounted into any runtime.
        temporary.rename(STATE)
        print(f"Created local CA and private configuration: {STATE}; no system trust changed")
        return environment()
    except BaseException:
        # Preserve private diagnostics, never delete an operator's pre-existing state.
        print(f"Incomplete private initialization retained at {temporary}")
        raise


def environment():
    if STATE.is_symlink(): raise RuntimeError("P1 state must not be a symlink")
    owner = json.loads((STATE / "owner.json").read_text())
    if owner != {"project":PROJECT,"root":str(ROOT)}: raise RuntimeError("State belongs to another project/checkout")
    env = dict(os.environ)
    for line in (STATE / "environment.env").read_text().splitlines():
        key, value = line.split("=",1)
        if not re.fullmatch(r"[A-Z_0-9]+",key): raise RuntimeError("Invalid environment key")
        env[key] = value
    https_port=port("P1_HTTPS_PORT","3443",env)
    port("P1_RTMPS_PORT","11936",env)
    if env["WEB_ORIGIN"]!="https://localhost:"+https_port:raise RuntimeError("Origin and HTTPS port differ; keep the initialized ports")
    return env


def port(key,default,env=None):
    value=(os.environ if env is None else env).get(key,default)
    if not value.isascii() or not value.isdecimal() or not 1024<=int(value)<=65535:
        raise RuntimeError(key+" must be an unprivileged TCP port")
    return value


def command(env, *args, s3=False, timeout=180):
    cmd = ["docker","compose","--env-file",str(STATE / "environment.env"),"-p",PROJECT,"-f",str(HERE / ("compose.s3.yaml" if s3 else "compose.yaml"))]
    if s3 and env.get("CORE_IMAGE_S3_ENDPOINT") and not env["CORE_IMAGE_S3_ENDPOINT"].startswith("https://"):
        raise RuntimeError("The TLS profile requires an HTTPS S3 endpoint")
    return run([*cmd,*args],cwd=ROOT,env=env,timeout=timeout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("init","build","up","status","down","reset"))
    parser.add_argument("--s3", action="store_true", help="External private S3; configure its environment first")
    parser.add_argument("--replicas", type=int, choices=(1,2), default=1)
    parser.add_argument("--confirm-disposable", action="store_true", help="Required to remove only this project's volumes")
    args = parser.parse_args()
    # Lock outside .state so initialization is serialized too.
    with open(HERE / ".manage.lock","a") as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        env = initialize() if args.action=="init" else environment()
        if args.action=="build": command(env,"build",s3=args.s3,timeout=1800); print("Current runtime images built")
        elif args.action=="up":
            (STATE / "Caddyfile").write_text(render_proxy())
            command(env,"up","-d","--scale",f"core={args.replicas}","--scale",f"chat={args.replicas}",s3=args.s3)
            web_id = command(env,"ps","-q","web",s3=args.s3).strip()
            networks = json.loads(run(["docker","inspect",web_id]))[0]["NetworkSettings"]["Networks"]
            if len(networks)!=1: raise RuntimeError("Review trusted proxy when changing topology")
            env["WEB_PROXY_IP"] = next(iter(networks.values()))["IPAddress"]
            path = STATE / "environment.env"
            lines = [line for line in path.read_text().splitlines() if not line.startswith("WEB_PROXY_IP=")]
            path.write_text("\n".join([*lines,"WEB_PROXY_IP="+env["WEB_PROXY_IP"]])+"\n")
            command(env,"up","-d","--no-deps","--scale",f"core={args.replicas}","core",s3=args.s3)
            print(f"Started {PROJECT}: {env['WEB_ORIGIN']}; wait for health/status before use")
        elif args.action=="status": print(command(env,"ps",s3=args.s3))
        elif args.action=="down": command(env,"down",s3=args.s3); print("Stopped; persistent volumes retained")
        elif args.action=="reset":
            if not args.confirm_disposable: raise RuntimeError("Reset requires --confirm-disposable; only streaming-p1 volumes are removed")
            command(env,"down","--volumes","--remove-orphans",s3=args.s3); print("Only streaming-p1 disposable volumes removed; private config retained")


if __name__=="__main__": main()
