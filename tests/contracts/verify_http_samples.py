"""Validate actual wire responses collected by the real Rust consumer, without printing data."""
import importlib.util
import json
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("contracts", Path(__file__).resolve().parents[2] / "contracts/generate.py")
contracts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contracts)

bundle, _ = contracts.load()
samples = json.loads(Path(sys.argv[1]).read_text())
if not samples or {s["schema"] for s in samples} != {"OwnerContext", "CatalogValues", "Error"}:
    raise SystemExit("Missing real-provider contract samples")
for sample in samples:
    try:
        contracts.validate_payload(bundle, sample["schema"], sample["payload"], sample["public"])
    except (ValueError, contracts.ValidationError):
        raise SystemExit("Real-provider contract sample failed; inspect locally without publishing payloads") from None
print(f"PASS: {len(samples)} real-provider payloads validated independently against neutral schemas")
