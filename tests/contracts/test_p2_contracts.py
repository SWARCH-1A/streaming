"""RF-004's provider/consumer schemas and generated artifacts remain consistent offline."""
import copy
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "contracts"))
import check_p2


class P2ContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base, cls.additions, cls.merged, cls.sdl = check_p2.load()

    def test_generated_artifacts_match_the_current_sources(self):
        check_p2.verify(self.base, self.additions, self.merged, self.sdl)
        for name, expected in check_p2.artifacts(self.additions, self.merged, self.sdl).items():
            self.assertEqual((check_p2.OUTPUT / name).read_text(), expected, name)

    def test_private_delivery_material_cannot_enter_the_broker(self):
        value = copy.deepcopy(self.additions["schemas"]["P2EventCredentialRecoveryDeliveryRequested"]["examples"][0])
        value["payload"]["recoveryToken"] = "fixture-only-secret"
        with self.assertRaises((ValueError, check_p2.ValidationError)):
            check_p2.validate_payload(self.merged, "P2EventCredentialRecoveryDeliveryRequested", value, event=True)

    def test_base_schemas_cannot_be_silently_replaced(self):
        self.assertFalse(set(self.base["schemas"]) & set(self.additions["schemas"]))

    def test_unknown_operation_schema_and_missing_idempotency_are_rejected(self):
        for change in ("schema", "idempotency"):
            altered = copy.deepcopy(self.additions)
            if change == "schema":
                altered["operations"][0]["request"] = "MissingSchema"
            else:
                altered["operations"][0]["requiredHeaders"] = []
            with self.assertRaises(ValueError):
                check_p2.verify(self.base, altered, self.merged, self.sdl)

    def test_pending_interfaces_keep_their_owner_decisions(self):
        pending = {item["id"]: item["blockedBy"] for item in self.additions["pendingInterfaces"]}
        self.assertEqual(pending["watch-parties"], ["P2-01", "P2-02", "P2-03"])
        self.assertEqual(pending["subtitles"], ["P2-04", "P2-05"])


if __name__ == "__main__":
    unittest.main()
