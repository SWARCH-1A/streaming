"""Exercise the contract gate against accidental and incompatible source changes."""
import copy
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from jsonschema.exceptions import ValidationError

spec = importlib.util.spec_from_file_location("contracts", Path(__file__).resolve().parents[2] / "contracts/generate.py")
contracts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contracts)


class ContractGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.bundle, cls.sdl = contracts.load()

    def test_canonical_examples_and_core_sdl(self):
        contracts.verify(self.bundle, self.sdl)
        contracts.check_core_sdl(self.sdl)

    def test_media_control_inventories_both_publisher_collections(self):
        operations = {(op["method"], op["path"]): op for op in self.bundle["operations"]
                      if op["provider"] == "MediaMTX"}
        for protocol in ("rtmp", "rtmps"):
            for method, action, success in (("GET", "get", "MediaControlStatus"),
                                            ("POST", "kick", None)):
                with self.subTest(protocol=protocol, action=action):
                    op = operations[(method, f"/v3/{protocol}/conns/{action}/{{publisherId}}")]
                    self.assertEqual(op["auth"], "private-basic")
                    self.assertEqual(op["visibility"], "private")
                    self.assertEqual(op["responses"]["200"], success)
                    self.assertEqual(op["responses"]["404"], "MediaControlError")
                    self.assertEqual(op["timeoutMs"], 2000)

    def test_generation_is_deterministic_and_drift_fails(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(contracts, "OUTPUT", Path(directory)):
            with patch.object(sys, "argv", ["generate.py", "--write"]):
                contracts.main()
            with patch.object(sys, "argv", ["generate.py", "--check"]):
                contracts.main()
                schema = Path(directory) / "p1.schema.json"
                schema.write_text(schema.read_text() + " ")
                with self.assertRaisesRegex(ValueError, "drift"):
                    contracts.main()

    def test_local_references_only(self):
        for target in ("https://example.test/schema.json", "#/$defs/Missing"):
            bundle = copy.deepcopy(self.bundle)
            bundle["schemas"]["PublicIdentity"]["properties"]["handle"] = {"$ref": target}
            with self.assertRaisesRegex(ValueError, "Nonlocal or unresolved"):
                contracts.verify(bundle, self.sdl)

    def test_ambiguous_json_and_duplicate_operation_fail(self):
        with self.assertRaisesRegex(ValueError, "Duplicate JSON key"):
            contracts.read_json('{"version":1,"version":2}')
        bundle = copy.deepcopy(self.bundle)
        bundle["operations"].append(bundle["operations"][0])
        with self.assertRaisesRegex(ValueError, "Duplicate operation"):
            contracts.verify(bundle, self.sdl)

    def test_dynamic_references_cannot_escape_local_bundle(self):
        for keyword in ("$id", "$dynamicRef"):
            bundle = copy.deepcopy(self.bundle)
            bundle["schemas"]["PublicIdentity"][keyword] = "https://example.test/schema.json"
            with self.assertRaisesRegex(ValueError, "not allowed"):
                contracts.verify(bundle, self.sdl)

    def test_additive_response_allowed_but_required_field_breaks(self):
        value = copy.deepcopy(self.bundle["schemas"]["PublicIdentity"]["examples"][0])
        value["newPublicField"] = {"label": "Compatible"}
        contracts.validate_payload(self.bundle, "PublicIdentity", value, public=True)
        del value["userId"]
        with self.assertRaises(ValidationError):
            contracts.validate_payload(self.bundle, "PublicIdentity", value, public=True)

    def test_nested_public_secrets_rejected_in_success_and_errors(self):
        for name in ("PublicIdentity", "Error"):
            value = copy.deepcopy(self.bundle["schemas"][name]["examples"][0])
            value["extra"] = [{"credential": "fictitious-secret"}]
            with self.assertRaisesRegex(ValueError, "Private fields"):
                contracts.validate_payload(self.bundle, name, value, public=True)

    def test_response_field_rename_and_graphql_break_detected(self):
        bundle = copy.deepcopy(self.bundle)
        bundle["schemas"]["PublicIdentity"]["required"].append("renamedHandle")
        with self.assertRaises(ValidationError):
            contracts.verify(bundle, self.sdl)
        with self.assertRaisesRegex(ValueError, "Invalid GraphQL"):
            contracts.verify(self.bundle, self.sdl.replace("viewerCountFresh: Boolean!", "renamedCount: Boolean!"))

    def test_utc_format_and_schema_examples_mandatory(self):
        value = copy.deepcopy(self.bundle["schemas"]["ChatMessage"]["examples"][0])
        value["serverCreatedAtUtc"] = "2026-10-08T12:00:00-05:00"
        with self.assertRaises(ValidationError):
            contracts.validate_payload(self.bundle, "ChatMessage", value)
        bundle = copy.deepcopy(self.bundle)
        bundle["schemas"]["PublicIdentity"]["examples"] = []
        with self.assertRaisesRegex(ValueError, "Missing example"):
            contracts.verify(bundle, self.sdl)


if __name__ == "__main__":
    unittest.main()
