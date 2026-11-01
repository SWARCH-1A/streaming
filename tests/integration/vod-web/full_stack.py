"""Run the real RTMPS/Chat/VOD and browser scenarios in a disposable Compose project.

Linux/WSL, Docker Compose, OpenSSL and Node (or Windows node.exe under WSL).
Uses the canonical initialization and Compose definitions, but unique state, images,
ports, networks and volumes. Never reads or changes the personal .state-local.
"""
from pathlib import Path
import argparse
import base64
import importlib.util
import json
import os
import runpy
import shutil
import socket
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[3]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def isolate_images(services, project):
    images = {service["image"]: project + ":" + service["image"].replace(":", "-").replace("/", "-")
              for service in services.values() if "build" in service}
    for service in services.values():
        # Initializers without a build also use the image built by their provider.
        if service.get("image") in images:
            service["image"] = images[service["image"]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", required=True, choices=("subtitles.mjs", "administration.mjs"))
    args = parser.parse_args()
    if os.name == "nt":
        raise RuntimeError("Run full_stack.py from Linux/WSL")
    node = shutil.which("node") or shutil.which("node.exe")
    if not node:
        raise RuntimeError("Node is required (Windows node.exe is supported in WSL)")
    os.environ["P1_PROFILE"] = "local"
    # Do not inherit personal ports or network selections.
    for key in ("P1_HTTPS_PORT", "P1_RTMPS_PORT", "LOCAL_SUBNET", "LOCAL_PROXY_IP", "LOCAL_GATEWAY_IP"):
        os.environ.pop(key, None)
    project = "streaming-vod-web-" + uuid.uuid4().hex[:10]
    manager = load("fixture_manager", ROOT / "infra/p1/manage.py")
    replay = load("fixture_replay", ROOT / "tests/integration/p2-chat-replay/run.py")
    with tempfile.TemporaryDirectory(prefix=project, delete=False) as directory:
        temp = Path(directory).resolve()
        manager.HERE, manager.STATE, manager.PROJECT = temp, temp / "state", project
        manager.HTTPS_PORT, manager.RTMPS_PORT = str(free_port()), str(free_port())
        env = manager.initialize()
        for relative in ("infra/broker/bootstrap.py", "infra/local/recovery_config.py",
                         "infra/local/notifications_config.py", "infra/local/vod_config.py", "infra/local/chat_config.py"):
            env = runpy.run_path(str(ROOT / relative))["initialize"](env, manager.STATE, manager.run)
        config = json.loads(manager.command(env, "config", "--format", "json"))
        config["name"] = project
        for kind in ("networks", "volumes"):
            for name, value in config.get(kind, {}).items():
                if value.get("external"):
                    raise RuntimeError("Disposable runner must not attach external resources")
                value["name"] = project + "_" + name
        isolate_images(config["services"], project)
        for name, service in config["services"].items():
            service.pop("container_name", None)
            service.pop("ports", None)
            service["restart"] = "no"
        proxy_port = int(env["P1_HTTPS_PORT"])
        config["services"]["proxy"]["ports"] = [{"target": 3443, "published": str(proxy_port), "host_ip": "127.0.0.1", "protocol": "tcp"}]
        broker_port = free_port()
        config["services"]["broker"]["ports"] = [{"target": 15671, "published": str(broker_port), "host_ip": "127.0.0.1", "protocol": "tcp"}]
        compose_file = temp / "compose.json"
        compose_file.write_text(json.dumps(config))
        compose_file.chmod(0o600)
        command = ["docker", "compose", "-p", project, "-f", str(compose_file)]

        def compose(*arguments, timeout=180):
            return manager.run([*command, *arguments], env=env, cwd=ROOT, timeout=timeout)

        started = False
        try:
            print("Building isolated full-stack images from current checkout", flush=True)
            compose("build", timeout=2400)
            started = True
            compose("up", "-d", "--wait", "--wait-timeout", "240", timeout=360)
            print("Isolated full stack healthy; creating two native recordings with real Chat", flush=True)
            replay.configure(manager.STATE, project, origin=env["WEB_ORIGIN"], live_image=config["services"]["live"]["image"])
            first = replay.main(keep=True, minimum_seconds=70)
            second = replay.main(keep=True, minimum_seconds=70, existing_owner=first)
            if min(first["vod"]["durationMs"], second["vod"]["durationMs"]) < 64000:
                raise AssertionError("Native recordings must exceed 64 seconds")
            containers = {name: compose("ps", "-q", name).strip() for name in ("core-db", "core", "vod", "chat")}
            if not all(containers.values()):
                raise RuntimeError("Missing fixture service")
            operator = env["BROKER_OPERATOR_PASSWORD"]
            fixture = {"origin": env["WEB_ORIGIN"], "prefix": project, "containers": containers,
                       "dbUser": "core", "owner": first["owner"], "handle": first["handle"], "password": first["password"],
                       "vods": [first["vod"], second["vod"]], "replay": [first["messages"], second["messages"]],
                       "broker": {"url": f"https://localhost:{broker_port}", "authorization": "Basic " + base64.b64encode(("operator:" + operator).encode()).decode()},
                       "cookies": [{"name": c.name, "value": c.value, "domain": "localhost", "path": c.path,
                                    "secure": c.secure, "httpOnly": c.has_nonstandard_attr("HttpOnly"), "sameSite": "Lax"} for c in first["client"].cookies]}
            state_file = temp / "browser.json"
            state_file.write_text(json.dumps(fixture))
            state_file.chmod(0o600)
            script = Path(__file__).with_name(args.browser)
            def node_path(path):
                if node.endswith(".exe"):
                    return subprocess.check_output(["wslpath", "-w", str(path)], text=True).strip()
                return str(path)
            # Pipe private fixture data directly: Windows Node cannot reliably open
            # Linux /tmp paths through WSL's UNC bridge. Never expose it in argv.
            browser_env = dict(os.environ)
            browser_env.pop("VOD_WEB_FIXTURE", None)
            subprocess.run([node, node_path(script)], cwd=ROOT, env=browser_env,
                           input=json.dumps(fixture), text=True, check=True)
            print("PASS full-stack browser scenario with real RTMPS, replay and isolated persistence", flush=True)
        except BaseException:
            if started:
                print(compose("ps", "--all"), flush=True)
                # Save diagnostics privately; never echo provider logs containing credentials.
                (temp / "services.log").write_text(compose("logs", "--tail", "60"))
            print(f"Private fixture diagnostics retained in {temp}; do not share secrets", flush=True)
            raise
        finally:
            if started:
                # Only resources in the generated, local-only config with this unique -p.
                compose("down", "--volumes", "--remove-orphans", timeout=180)


if __name__ == "__main__":
    main()
