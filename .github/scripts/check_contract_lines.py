"""Check the summary lines the tests wrote (build/contract-lines/*.txt) against the shared contracts
in contracts/events/ (copies of the platform's, kept in step by the Valxel SDLC). Exits 1, naming
each problem, when a line breaks its contract or no lines were written."""

from __future__ import annotations

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

CONTRACTS = {"valxel.events": "valxel.events.v1", "valxel.flag_cohorts": "valxel.flag_cohorts.v1"}


def main() -> int:
    validators = {m: Draft202012Validator(json.loads(Path(f"contracts/events/{n}.schema.json").read_text(encoding="utf-8")))
                  for m, n in CONTRACTS.items()}
    lines = [ln for f in sorted(Path("build/contract-lines").glob("*.txt"))
             for ln in f.read_text(encoding="utf-8").splitlines() if ln.strip()]
    seen, bad = set(), 0
    for line in lines:
        marker, _, payload = line.partition(" ")
        if marker not in validators:
            print(f"not a contract line: {line[:120]}")
            bad += 1
            continue
        seen.add(marker)
        for err in validators[marker].iter_errors(json.loads(payload)):
            print(f"{marker}: {'/'.join(map(str, err.absolute_path)) or 'line'}: {err.message}")
            bad += 1
    missing = sorted(set(CONTRACTS) - seen)
    if missing:
        print(f"no {', '.join(missing)} line was written: did RelayContractLinesTest run?")
        bad += 1
    print(f"{len(lines)} lines checked against contracts/events: {bad} problems")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
