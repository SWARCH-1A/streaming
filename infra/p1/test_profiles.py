"""Profile ownership and destructive-command isolation; no Docker operations."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


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
        for profile in ("default", "load"):
            selected = module(profile)
            env = {"P1_STATE": str(selected.STATE)}
            with patch.object(selected, "run", return_value="") as run:
                selected.command(env, "down", "--volumes", "--remove-orphans")
                args = run.call_args.args[0]
                self.assertEqual(args[args.index("-p") + 1], selected.PROJECT)
                self.assertEqual(args[args.index("--env-file") + 1], str(selected.STATE / "environment.env"))
                self.assertEqual(run.call_args.kwargs["env"], env)
                with self.assertRaisesRegex(RuntimeError, "Command state"):
                    selected.command({"P1_STATE": "/foreign/state"}, "down", "--volumes")
                self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
