"""Security evidence must preserve producer attribution and exclude private fields."""
import copy
import importlib.util
from pathlib import Path
import sys
import unittest
from jsonschema.exceptions import ValidationError

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "contracts"))
SPEC = importlib.util.spec_from_file_location("audit_p2_contracts", ROOT / "contracts/check_p2.py")
p2 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p2)


class SecurityAuditContracts(unittest.TestCase):
    def setUp(self):
        self.source, self.schema = p2.schema_bundle()

    def test_trusted_coordinates_and_payload(self):
        for producer in ("Core", "Vod"):
            name = "P2EventSecurityAudit" + producer
            envelope = self.source["schemas"][name]["examples"][0]
            p2.validate(self.schema, name, envelope)
            for override in ({"sequence": 2}, {"aggregateId": "audit:00000000-0000-0000-0000-000000000000"}):
                with self.assertRaises((ValueError, ValidationError)):
                    p2.validate(self.schema, name, {**envelope, **override})
            for field, value in (("password", "private"), ("reasonCode", "unbounded free text"),
                                 ("actorId", "forged\nentry"), ("targetType", "SECRET")):
                event = copy.deepcopy(envelope)
                event["payload"][field] = value
                with self.subTest(producer=producer, field=field), self.assertRaises(ValidationError):
                    p2.validate(self.schema, name, event)

    def test_null_actor_only_for_denials(self):
        name = "P2EventSecurityAuditVod"
        event = copy.deepcopy(self.source["schemas"][name]["examples"][0])
        event["payload"].update(actorId=None, outcome="DENIED")
        p2.validate(self.schema, name, event)
        event["payload"]["outcome"] = "SUCCESS"
        with self.assertRaises(ValidationError):
            p2.validate(self.schema, name, event)

    def test_bounded_query(self):
        for query in ({"limit": 0}, {"limit": 101}, {"cursor": "-1"}, {"token": "secret"}, {"requestId": "x" * 81}):
            with self.assertRaises(ValidationError):
                p2.validate(self.schema, "P2AuditQuery", query)


if __name__ == "__main__":
    unittest.main()
