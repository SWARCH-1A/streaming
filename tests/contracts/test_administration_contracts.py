"""Neutral administration contracts reject ambiguous commands and private fields."""
import importlib.util
from pathlib import Path
import unittest
import sys

from jsonschema.exceptions import ValidationError

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "contracts"))
SPEC = importlib.util.spec_from_file_location("p2_admin_contracts", Path(__file__).resolve().parents[2] / "contracts/check_p2.py")
p2 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p2)


class AdministrationContracts(unittest.TestCase):
    def setUp(self):
        self.source, self.schema = p2.schema_bundle()

    def test_commands_require_bounded_version_reason_and_known_status(self):
        command = dict(status="SUSPENDED", expectedVersion=0, reason="Prueba")
        p2.validate(self.schema, "P2AccountStatusCommand", command)
        for invalid in ({**command, "expectedVersion": -1}, {**command, "expectedVersion": 1.5},
                        {**command, "expectedVersion": 9007199254740991}, {**command, "reason": "   "},
                        {**command, "status": "DELETED"}, {**command, "role": "ADMIN"}):
            with self.subTest(invalid=invalid), self.assertRaises(ValidationError):
                p2.validate(self.schema, "P2AccountStatusCommand", invalid)

    def test_lists_do_not_leak_identity_secrets(self):
        account = dict(userId="usr_fixture", handle="fixture", status="ACTIVE", accountVersion=0)
        p2.validate(self.schema, "P2AccountAdminPage", dict(items=[account], nextCursor=None))
        for key in ("password_hash", "email", "credential", "account_role"):
            with self.subTest(key=key), self.assertRaises(ValidationError):
                p2.validate(self.schema, "P2AccountAdminPage", dict(items=[{**account, key: "private"}], nextCursor=None))

    def test_pagination_limits_and_unknown_query_fields(self):
        p2.validate(self.schema, "P2PageQuery", {"limit": 50, "cursor": "opaque"})
        for query in ({"limit": 0}, {"limit": 51}, {"role": "ADMIN"}):
            with self.assertRaises(ValidationError):
                p2.validate(self.schema, "P2PageQuery", query)

    def test_event_coordinates_agree(self):
        envelope = self.source["schemas"]["P2EventAccountStatusChanged"]["examples"][0]
        p2.validate(self.schema, "P2EventAccountStatusChanged", envelope)
        for override in ({"sequence": envelope["sequence"] + 1}, {"aggregateId": "account:someone_else"}):
            with self.assertRaises(ValueError):
                p2.validate(self.schema, "P2EventAccountStatusChanged", {**envelope, **override})


if __name__ == "__main__":
    unittest.main()
