from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    json_contracts = [
        ROOT / "contracts" / "events" / "envelope.schema.json",
        ROOT / "contracts" / "connectors" / "manifest.schema.json",
        ROOT / "contracts" / "poc" / "t1" / "provider-profile.schema.json",
        ROOT / "contracts" / "poc" / "t1" / "payment-operation.schema.json",
        ROOT / "contracts" / "poc" / "t1" / "device-operation.schema.json",
        ROOT / "contracts" / "poc" / "t1" / "fault-script.schema.json",
        ROOT / "contracts" / "poc" / "t1" / "sync-event.schema.json",
        ROOT / "contracts" / "poc" / "t1" / "data-package.schema.json",
        ROOT / "contracts" / "poc" / "t1" / "upgrade-case.schema.json",
        ROOT / "contracts" / "poc" / "t1" / "evidence.schema.json",
    ]
    for path in json_contracts:
        with path.open(encoding="utf-8") as handle:
            document = json.load(handle)
        if document.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            raise SystemExit(f"CONTRACT ERROR: {path} is not JSON Schema 2020-12")
        if not document.get("$id"):
            raise SystemExit(f"CONTRACT ERROR: {path} has no $id")

    openapi = (ROOT / "contracts" / "openapi" / "openapi.yaml").read_text(encoding="utf-8")
    for token in ("openapi: 3.1.0", "version: 0.0.0-t0", "/internal/health:"):
        if token not in openapi:
            raise SystemExit(f"CONTRACT ERROR: OpenAPI missing {token}")

    print(f"CONTRACTS OK: {len(json_contracts)} JSON schemas and OpenAPI T0 skeleton")


if __name__ == "__main__":
    try:
        main()
    except (OSError, json.JSONDecodeError) as exc:
        print(f"CONTRACT ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
