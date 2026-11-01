"""Configuration safety: replay imports must not inspect the user's local deployment."""
from pathlib import Path
import importlib.util
import tempfile
import unittest
from unittest.mock import Mock, patch

SOURCE = Path(__file__).resolve().parents[1] / "p2-chat-replay/run.py"


def load():
    spec = importlib.util.spec_from_file_location("test_replay_runner", SOURCE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ReplayConfigurationTest(unittest.TestCase):
    def test_second_recording_requires_existing_config_and_explicit_key_rotation(self):
        module = load()
        owner = Mock()
        owner.call.return_value = (200, {"streamId": "str_fixture"})
        owner.mutate.side_effect = [(200, {}), (200, {"streamId": "str_fixture", "streamKey": "new-fixture-key"})]
        config, key = module.prepare_stream(owner, "chn_fixture", "cat_fixture", existing=True)
        self.assertEqual(owner.call.call_args.args[-1], 200)
        self.assertEqual(owner.mutate.call_args_list[0].args[:2], ("/api/streams/str_fixture", "PATCH"))
        self.assertEqual(owner.mutate.call_args_list[1].args, ("/api/streams/str_fixture/ingest-keys/rotate",))
        self.assertEqual(key, "new-fixture-key")
        self.assertEqual(config["streamId"], "str_fixture")

    def test_first_recording_requires_created_config_and_its_one_time_key(self):
        module = load()
        owner = Mock()
        owner.call.return_value = (201, {"streamId": "str_fixture", "streamKey": "first-fixture-key"})
        _, key = module.prepare_stream(owner, "chn_fixture", "cat_fixture")
        self.assertEqual(owner.call.call_args.args[-1], 201)
        owner.mutate.assert_not_called()
        self.assertEqual(key, "first-fixture-key")

    def test_import_does_not_read_personal_state_or_certificate(self):
        with patch.object(Path, "read_text", side_effect=AssertionError("Unexpected state read")), \
                patch("ssl.create_default_context", side_effect=AssertionError("Unexpected certificate read")):
            module = load()
        self.assertIsNone(module.TLS)
        with self.assertRaisesRegex(RuntimeError, "Call configure"):
            module.main()

    def test_explicit_fixture_selects_only_its_certificate_and_origin(self):
        module = load()
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            (state / "environment.env").write_text("WEB_ORIGIN=https://localhost:40001\n")
            with patch("ssl.create_default_context") as context:
                context.return_value.verify_flags = 0
                module.configure(state, "fixture", "fixture_network", "https://localhost:40002", "fixture-live")
                context.assert_called_once_with(cafile=str(state.resolve() / "ca.crt"))
            self.assertEqual(module.ORIGIN, "https://localhost:40002")
            self.assertEqual(module.NETWORK, "fixture_network")
            self.assertEqual(module.LIVE_IMAGE, "fixture-live")


if __name__ == "__main__":
    unittest.main()
