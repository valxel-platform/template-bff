"""Check the audit events the tests wrote (build/audit-events/*.jsonl) against the shared contract,
contracts/audit/valxel.audit.v1.schema.json (a copy of valxel-shared-services/ss-audit-sink's).
Exits 1, naming each problem, when an event breaks it or no events were written."""

from __future__ import annotations

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator


def main() -> int:
    schema = json.loads(Path("contracts/audit/valxel.audit.v1.schema.json").read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema)
    events = [json.loads(line) for f in sorted(Path("build/audit-events").glob("*.jsonl"))
              for line in f.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not events:
        print("no audit events were written: did SignInAuditSchemaContractTest run?")
        return 1
    bad = 0
    for i, event in enumerate(events):
        for err in validator.iter_errors(event):
            where = "/".join(str(p) for p in err.absolute_path) or "event"
            print(f"event {i} ({event.get('data', {}).get('action', '?')}): {where}: {err.message}")
            bad += 1
    print(f"{len(events)} audit events checked against {schema.get('x-version')}: {bad} problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
