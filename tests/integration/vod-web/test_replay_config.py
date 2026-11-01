"""Configuration safety: replay imports must not inspect the user's local deployment."""
from pathlib import Path
import importlib.util
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / "p2-chat-replay/run.py"


def load():
    spec = importlib.util.spec_from_file_location("test_replay_runner", SOURCE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ReplayConfigurationTest(unittest.TestCase):
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
