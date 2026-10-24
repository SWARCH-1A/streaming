#!/usr/bin/env python3
"""Exercise the broker configuration and operator image in an isolated Docker network."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import runpy
import secrets
import shutil
import subprocess
import tempfile
import time
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def main():
    project = "streaming-broker-smoke-" + secrets.token_hex(6)
    state = Path(tempfile.mkdtemp(prefix=project + "-"))
    os.chmod(state, 0o700)
    log_path = state / "diagnostics.log"
    resources = []
    image = project + "-ops"
    successful = False

    def run(args, check=True, **kwargs):
        result = subprocess.run(args, capture_output=True, text=True, timeout=kwargs.pop("timeout", 180), **kwargs)
        with log_path.open("a") as target:
            target.write(result.stdout + result.stderr)
        os.chmod(log_path, 0o600)
        if check and result.returncode:
            raise RuntimeError(f"{Path(args[0]).name} {args[1]} failed; private diagnostics: {log_path}")
        return result

    def wait_running():
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            container_state = json.loads(run(["docker", "inspect", "--format", "{{json .State}}", project]).stdout)
            if container_state["Status"] in ("exited", "dead"):
                run(["docker", "logs", project], check=False)
                raise RuntimeError(f"RabbitMQ exited during startup; private diagnostics: {log_path}")
            # A root CLI started before the server can create a root-owned Erlang cookie.
            if run(["docker", "exec", "--user", "rabbitmq", project, "rabbitmq-diagnostics", "-q", "check_port_connectivity"], check=False, timeout=15).returncode == 0:
                return
            time.sleep(1)
        raise RuntimeError(f"RabbitMQ did not become ready; private diagnostics: {log_path}")

    def client(phase):
        result = run(["docker", "run", "--rm", "--network", project,
                      "--env-file", str(state / "environment.env"), "--entrypoint", "python",
                      "--mount", f"type=bind,source={state / 'ca.crt'},target=/run/secrets/ca.crt,readonly",
                      "--mount", f"type=bind,source={state / 'fixture.json'},target=/fixture.json,readonly",
                      "--mount", f"type=bind,source={HERE / 'smoke_client.py'},target=/smoke_client.py,readonly",
                      "--mount", f"type=volume,source={project}-audit,target=/audit",
                      image, "/smoke_client.py", phase], timeout=90)
        print(result.stdout.strip(), flush=True)

    try:
        (state / "environment.env").write_text("")
        os.chmod(state / "environment.env", 0o600)
        run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key",
             "-out", "ca.crt", "-days", "2", "-sha256", "-subj", "/CN=Disposable broker smoke CA"], cwd=state)
        setup = runpy.run_path(str(HERE / "bootstrap.py"))
        setup["initialize"]({}, state, run)
        definitions = json.loads((state / "broker-definitions.json").read_text())
        event = json.loads((ROOT / "contracts/p2.json").read_text())["schemas"]["P2EventStreamSessionChanged"]["examples"][0]
        event.update(eventId=str(uuid.uuid4()), occurredAtUtc=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"))
        event["correlationId"] = event["eventId"]
        (state / "fixture.json").write_text(json.dumps({"event": event, "queues": [q["name"] for q in definitions["queues"]]}))
        broker_image = re.search(r"^\s+image: (rabbitmq:\S+)$", (HERE / "compose.yaml").read_text(), re.MULTILINE)
        if broker_image is None:
            raise RuntimeError("The broker image is missing from compose.yaml")
        run(["docker", "build", "-t", image, "-f", str(HERE / "Dockerfile.operator"), str(HERE)], timeout=600)
        resources.append(["docker", "image", "rm", image])
        run(["docker", "run", "--rm", "--network", "none", image, "--help"])
        print("PASS: operator image imports and displays its CLI without credentials or network", flush=True)
        run(["docker", "network", "create", project])
        resources.append(["docker", "network", "rm", project])
        for suffix in ("data", "audit"):
            run(["docker", "volume", "create", project + "-" + suffix])
            resources.append(["docker", "volume", "rm", project + "-" + suffix])
        command = ["docker", "run", "-d", "--name", project, "--hostname", "broker", "--network", project,
                   "--network-alias", "broker", "--env", "RABBITMQ_NODENAME=rabbit@broker",
                   "--mount", f"type=volume,source={project}-data,target=/var/lib/rabbitmq",
                   "--mount", f"type=bind,source={HERE / 'rabbitmq.conf'},target=/etc/rabbitmq/rabbitmq.conf,readonly"]
        for name in ("broker-definitions.json", "broker.crt", "broker.key", "ca.crt"):
            command += ["--mount", f"type=bind,source={state / name},target=/run/secrets/{name},readonly"]
        run([*command, broker_image[1]])
        resources.append(["docker", "rm", "-f", project])
        wait_running()
        listeners = run(["docker", "exec", "--user", "rabbitmq", project, "rabbitmq-diagnostics", "-q", "listeners"]).stdout
        assert "port: 5671," in listeners and "port: 15671," in listeners
        assert "port: 5672," not in listeners and "port: 15672," not in listeners
        published = json.loads(run(["docker", "inspect", "--format", "{{json .HostConfig.PortBindings}}", project]).stdout)
        assert not published
        print("PASS: repository configuration starts with TLS-only AMQP/management and no published ports", flush=True)
        client("publish")
        run(["docker", "restart", project])
        wait_running()
        client("verify")
        successful = True
    finally:
        if not successful and ["docker", "rm", "-f", project] in resources:
            run(["docker", "logs", project], check=False)
        cleanup_ok = True
        for command in reversed(resources):
            if run(command, check=False).returncode:
                cleanup_ok = False
        if successful and cleanup_ok:
            shutil.rmtree(state)
        else:
            print(f"Private diagnostics retained at {log_path}", flush=True)
        if successful and not cleanup_ok:
            raise RuntimeError("Disposable broker cleanup failed; inspect the private diagnostics")


if __name__ == "__main__":
    main()
