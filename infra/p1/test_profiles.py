"""Profile ownership and destructive-command isolation; no Docker operations."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch


def module(profile="default"):
    with patch.dict(os.environ, {"P1_PROFILE": profile}):
        spec = importlib.util.spec_from_file_location("p1_profile_test", Path(__file__).with_name("manage.py"))
        value = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(value)
        return value


class Profiles(unittest.TestCase):
    def test_default_and_load_have_distinct_state_ports_project_and_lock(self):
        default, load = module(), module("load")
        self.assertEqual((default.STATE.name, default.PROJECT, default.HTTPS_PORT, default.RTMPS_PORT, default.LOCK_NAME),
                         (".state", "streaming-p1", "3443", "11936", ".manage.lock"))
        self.assertEqual((load.STATE.name, load.PROJECT, load.HTTPS_PORT, load.RTMPS_PORT, load.LOCK_NAME),
                         (".state-load", "streaming-p1-load", "3444", "11937", ".manage-load.lock"))
        self.assertEqual(default.configured_ports({"P1_HTTPS_PORT": "3450"}), ("3450", "11936"))

    def test_unknown_profile_and_load_port_override_fail(self):
        with self.assertRaisesRegex(RuntimeError, "P1_PROFILE"):
            module("other-project")
        load = module("load")
        with self.assertRaisesRegex(RuntimeError, "3444 and 11937"):
            load.configured_ports({"P1_HTTPS_PORT": "3443"})
        with self.assertRaisesRegex(RuntimeError, "unprivileged"):
            load.configured_ports({"P1_HTTPS_PORT": "443"})

    def test_state_owner_and_path_must_match_selected_profile(self):
        load = module("load")
        with tempfile.TemporaryDirectory() as directory:
            load.STATE = Path(directory)
            owner = load.STATE / "owner.json"
            env = load.STATE / "environment.env"
            owner.write_text(json.dumps({"project": load.PROJECT, "root": str(load.ROOT)}))
            env.write_text(f"P1_STATE={load.STATE}\nWEB_ORIGIN=https://localhost:3444\n")
            with patch.dict(os.environ, {"P1_HTTPS_PORT": "3444", "P1_RTMPS_PORT": "11937"}):
                self.assertEqual(load.environment()["P1_STATE"], str(load.STATE))
                env.write_text("P1_STATE=/foreign/state\nWEB_ORIGIN=https://localhost:3444\n")
                with self.assertRaisesRegex(RuntimeError, "State path"):
                    load.environment()
                owner.write_text(json.dumps({"project": "streaming-p1", "root": str(load.ROOT)}))
                with self.assertRaisesRegex(RuntimeError, "another project"):
                    load.environment()

    def test_symlink_state_is_rejected(self):
        load = module("load")
        with tempfile.TemporaryDirectory() as directory:
            load.STATE = Path(directory) / "linked"
            load.STATE.symlink_to(directory, target_is_directory=True)
            with self.assertRaisesRegex(RuntimeError, "symlink"):
                load.environment()

    def test_volume_removal_command_targets_only_selected_project(self):
        for profile in ("default", "load", "local"):
            selected = module(profile)
            env = {"P1_STATE": str(selected.STATE), "BROKER_STREAMING_PASSWORD": "fixture_private_password"}
            with patch.object(selected, "run", return_value="") as run:
                selected.command(env, "down", "--volumes", "--remove-orphans")
                args = run.call_args.args[0]
                self.assertEqual(args[args.index("-p") + 1], selected.PROJECT)
                self.assertEqual(args[args.index("--env-file") + 1], str(selected.STATE / "environment.env"))
                expected = selected.ROOT / "compose.yaml" if profile == "local" else selected.HERE / "compose.yaml"
                self.assertEqual(args[args.index("-f") + 1], str(expected))
                self.assertEqual(args.count("-f"), 2 if profile == "local" else 1)
                self.assertEqual(run.call_args.kwargs["env"], env)
                with self.assertRaisesRegex(RuntimeError, "Command state"):
                    selected.command({"P1_STATE": "/foreign/state"}, "down", "--volumes")
                self.assertEqual(run.call_count, 1)

    def test_local_profile_has_separate_state_and_verified_web_upstream(self):
        local = module("local")
        self.assertEqual((local.STATE.name, local.PROJECT, local.HTTPS_PORT, local.RTMPS_PORT, local.LOCK_NAME),
                         (".state-local", "streaming-local", "3445", "11938", ".manage-local.lock"))
        proxy = local.render_proxy()
        self.assertIn("https://live:8080", proxy)
        self.assertIn("https://web:3443", proxy)
        self.assertNotIn("file_server", proxy)
        self.assertNotIn("https://streaming:", proxy)
        self.assertNotIn("tls_insecure_skip_verify", proxy)
        self.assertIn("@core path /api/admin/*", proxy)
        self.assertIn("path / /admin /search", proxy)
        with self.assertRaisesRegex(RuntimeError, "filesystem"):
            local.command({"P1_STATE": str(local.STATE)}, "up", s3=True)

    def test_local_subnet_avoids_existing_docker_networks(self):
        local = module("local")
        with patch.dict(os.environ, {}, clear=True), patch.object(local, "run", side_effect=["network-id", '[{"IPAM":{"Config":null}},{"IPAM":{"Config":[{"Subnet":"192.168.240.0/24"}]}}]']):
            self.assertEqual(local.local_network(), {"LOCAL_SUBNET": "192.168.241.0/24", "LOCAL_PROXY_IP": "192.168.241.2", "LOCAL_DYNAMIC_RANGE": "192.168.241.128/25"})
        for invalid in ("192.168.240.0", "192.168.240.1", "192.168.240.255", "192.168.241.2", "192.168.240.130"):
            with patch.dict(os.environ, {"LOCAL_SUBNET": "192.168.240.0/24", "LOCAL_PROXY_IP": invalid}):
                with self.assertRaisesRegex(RuntimeError, "usable address"):
                    local.local_network()

    def test_broker_overlay_uses_local_state_and_project(self):
        local = module("local")
        env = {"P1_STATE": str(local.STATE), "BROKER_STREAMING_PASSWORD": "fixture_private_password"}
        with patch.object(local, "run", return_value="") as run:
            local.command(env, "--profile", "tools", "build")
            args = run.call_args.args[0]
            self.assertEqual(args[args.index("-p") + 1], "streaming-local")
            self.assertEqual([args[i + 1] for i, arg in enumerate(args) if arg == "-f"],
                             [str(local.ROOT / "compose.yaml"), str(local.ROOT / "infra/broker/compose.yaml")])
            self.assertEqual(args[-3:], ["--profile", "tools", "build"])

    def test_broker_requires_initialized_local_credentials(self):
        for profile in ("default", "load"):
            selected = module(profile)
            with patch.object(selected, "run") as run:
                with self.assertRaisesRegex(RuntimeError, "root local profile"):
                    selected.command({"P1_STATE": str(selected.STATE)}, "up", broker=True)
                run.assert_not_called()
        local = module("local")
        with patch.object(local, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "infra/local/dev.py init"):
                local.command({"P1_STATE": str(local.STATE)}, "up")
            run.assert_not_called()

    def test_local_cli_includes_broker_in_all_actions_without_a_flag(self):
        local = module("local")
        with tempfile.TemporaryDirectory() as directory:
            local.HERE = Path(directory)
            local.STATE = local.HERE / ".state-local"
            local.STATE.mkdir()
            env = {"P1_STATE": str(local.STATE), "WEB_ORIGIN": "https://localhost:3445"}
            bootstrap = Mock(return_value=env)
            with patch.object(local, "initialize", return_value=env) as initialize, \
                    patch.object(local, "environment", return_value=env), \
                    patch.object(local, "render_proxy", return_value="fixture proxy"), \
                    patch.object(local, "wait_local") as ready, \
                    patch.object(local, "command", return_value="") as command, \
                    patch("runpy.run_path", return_value={"initialize": bootstrap}), \
                    patch("builtins.print"):
                for action in ("init", "build", "up", "status", "down"):
                    with patch("sys.argv", ["dev.py", action]):
                        local.main()
                initialize.assert_called_once_with()
                bootstrap.assert_called_once_with(env, local.STATE, local.run)
                ready.assert_called_once_with(env)
                command.assert_any_call(env, "--profile", "tools", "build", s3=False, broker=True, timeout=1800)
                command.assert_any_call(env, "up", "-d", "--wait", "--wait-timeout", "180",
                                        "--scale", "core=1", "--scale", "chat=1",
                                        s3=False, broker=True, timeout=360)
                command.assert_any_call(env, "ps", s3=False, broker=True)
                command.assert_any_call(env, "down", s3=False, broker=True)

    def test_p1_cli_keeps_broker_disabled_by_default(self):
        for profile in ("default", "load"):
            selected = module(profile)
            with tempfile.TemporaryDirectory() as directory:
                selected.HERE = Path(directory)
                with patch.object(selected, "environment", return_value={}) as environment, \
                        patch.object(selected, "command", return_value="") as command, \
                        patch("sys.argv", ["manage.py", "status"]), patch("builtins.print"):
                    selected.main()
                    environment.assert_called_once_with()
                    command.assert_called_once_with({}, "ps", s3=False, broker=False)


if __name__ == "__main__":
    unittest.main()
