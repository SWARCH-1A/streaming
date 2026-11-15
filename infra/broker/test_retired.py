import base64
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from retired import retire


class RetirementTest(unittest.TestCase):
    def channel(self):
        channel = Mock()
        channel.queue_declare.return_value = SimpleNamespace(method=SimpleNamespace(consumer_count=0, message_count=0))
        properties = Mock()
        properties.encode.return_value = [b"properties"]
        channel.basic_get.side_effect = [(SimpleNamespace(delivery_tag=7), properties, b'{"original":true}'), (None, None, None)]
        return channel

    def test_exact_bytes_archived_before_ack_and_empty_guarded_delete(self):
        channel = self.channel()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "archive.jsonl"
            channel.basic_ack.side_effect = lambda _: self.assertTrue(path.exists())
            self.assertEqual(retire(channel, "chat.account", {"chat.account"}, "ADR-027", path), 1)
            row = json.loads(path.read_text())
            self.assertEqual(base64.b64decode(row["bodyBase64"]), b'{"original":true}')
            self.assertEqual(base64.b64decode(row["propertiesBase64"]), b"properties")
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        channel.queue_delete.assert_called_once_with("chat.account")
        channel.queue_unbind.assert_called_once_with("chat.account", "p2.events", "account.status.changed.v1")

    def test_archive_failure_never_acknowledges_or_deletes(self):
        channel = self.channel()
        with patch("retired.archive", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                retire(channel, "core.vod", {"core.vod"}, "ADR-027", "/unused")
        channel.basic_ack.assert_not_called()
        channel.queue_delete.assert_not_called()

    def test_active_consumer_or_current_subscription_refused(self):
        channel = self.channel()
        channel.queue_declare.return_value.method.consumer_count = 1
        with self.assertRaises(ValueError):
            retire(channel, "chat.account", {"chat.account"}, "ADR-027", "/unused")
        with self.assertRaises(ValueError):
            retire(channel, "chat.lifecycle", {"chat.lifecycle"}, "ADR-027", "/unused")
        channel.queue_unbind.assert_not_called()
        channel.basic_ack.assert_not_called()

    def test_message_appearing_before_delete_is_retained(self):
        channel = self.channel()
        channel.basic_get.return_value = (None, None, None)
        channel.basic_get.side_effect = None
        channel.queue_declare.side_effect = [
            SimpleNamespace(method=SimpleNamespace(consumer_count=0, message_count=0)),
            SimpleNamespace(method=SimpleNamespace(consumer_count=0, message_count=1)),
        ]
        with self.assertRaises(ValueError):
            retire(channel, "chat.account", {"chat.account"}, "ADR-027", "/unused")
        channel.queue_delete.assert_not_called()
