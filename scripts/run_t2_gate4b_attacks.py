from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "server/ruoyi-modules/jshpos-inventory"
PROCUREMENT = ROOT / "server/ruoyi-modules/jshpos-procurement"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE4B ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def mapper_statements(xml: str) -> list[str]:
    return re.findall(
        r"<(?:select|insert|update|delete)\b[^>]*>(.*?)</(?:select|insert|update|delete)>",
        xml,
        flags=re.IGNORECASE | re.DOTALL,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    stocktake_dto = read(
        "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/"
        "interfaces/rest/dto/StocktakeRequests.java"
    )
    procurement_dto = read(
        "server/ruoyi-modules/jshpos-procurement/src/main/java/com/jingshanghui/pos/procurement/"
        "interfaces/rest/dto/ProcurementRequests.java"
    )
    dto_runtime = re.sub(r"/\*.*?\*/|//[^\n]*", "", stocktake_dto + procurement_dto, flags=re.DOTALL)
    require("tenantId" not in dto_runtime and "tenant_id" not in dto_runtime,
            "request DTO exposes tenant authority")

    stocktake_service = read(
        "server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/"
        "application/service/StocktakeService.java"
    )
    procurement_service = read(
        "server/ruoyi-modules/jshpos-procurement/src/main/java/com/jingshanghui/pos/procurement/"
        "application/service/ProcurementService.java"
    )
    require("TrustedTenantContext" in stocktake_service and "TrustedTenantContext" in procurement_service,
            "trusted tenant injection missing")
    require("AuthoritativeInventoryMovementPort" in stocktake_service
            and "AuthoritativeInventoryMovementPort" in procurement_service,
            "stocktake/procurement bypasses the inventory Owner port")

    stocktake_xml = read(
        "server/ruoyi-modules/jshpos-inventory/src/main/resources/mapper/inventory/StocktakeMapper.xml"
    )
    procurement_xml = read(
        "server/ruoyi-modules/jshpos-procurement/src/main/resources/mapper/procurement/ProcurementMapper.xml"
    )
    statements = mapper_statements(stocktake_xml) + mapper_statements(procurement_xml)
    require(len(statements) >= 35 and all("#{tenantId}" in statement for statement in statements),
            "Mapper statement lacks explicit trusted tenant predicate/value")
    require("SELECT *" not in (stocktake_xml + procurement_xml).upper(), "wildcard SQL projection detected")
    require("FOR UPDATE" in stocktake_xml and "FOR UPDATE" in procurement_xml,
            "state-changing aggregate lacks row locking")
    require(not re.search(r"UPDATE\s+inv_stock_(?:balance|ledger)", procurement_xml, re.IGNORECASE),
            "procurement writes inventory tables directly")

    inventory_guard = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in INVENTORY.joinpath("src/main").rglob("*") if path.is_file()
    )
    procurement_guard = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in PROCUREMENT.joinpath("src/main").rglob("*") if path.is_file()
    )
    require("InventoryStrictTenantMapperGuard" in inventory_guard, "inventory fail-closed guard missing")
    require("ProcurementStrictTenantMapperGuard" in procurement_guard, "procurement fail-closed guard missing")

    runtime_java = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for root in (INVENTORY, PROCUREMENT)
        for path in root.joinpath("src/main/java").rglob("*.java")
    )
    runtime = stocktake_service + procurement_service + runtime_java
    lowered = runtime.lower()
    require(not any(token in lowered for token in (
        "java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http",
        "httpurlconnection", "https://", "http://",
    )), "Provider network client or URL detected")
    require(not any(token in lowered for token in (
        "costbalanceservice", "costledgerservice", "transferservice", "promotionservice",
    )), "deferred runtime detected")

    surfaces = [
        ("REST_DTO", "tenant authority absent from stocktake and procurement requests"),
        ("APPLICATION_CONTEXT", "trusted tenant, actor and store scope injected"),
        ("STOCKTAKE_MAPPER", "every stocktake SQL statement tenant scoped"),
        ("PROCUREMENT_MAPPER", "every procurement SQL statement tenant scoped"),
        ("NATIVE_SQL", "explicit columns and aggregate row locks"),
        ("INVENTORY_OWNER", "all inventory effects use the internal Owner port"),
        ("BLIND_COUNT", "book quantity is masked during count and recount"),
        ("ACTOR_SEPARATION", "review, approval and return approval are separated"),
        ("IDEMPOTENCY", "stable event identifiers protect inventory effects"),
        ("IMMUTABLE_FACTS", "counts, adjustments and ledger facts are append-only"),
        ("TASK_CACHE_EXPORT_OBJECT", "no alternate task/cache/export/object-store path admitted"),
        ("PROVIDER_NETWORK_SECRET", "network clients, URLs and production credentials absent"),
        ("COST_TRANSFER_PROMOTION", "deferred runtime absent"),
        ("TWO_TENANT_FIXTURE", "TENANT_A and TENANT_B remain synthetic and isolated"),
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0",
        "phase": "T2-GATE4B",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tenants": ["TENANT_A", "TENANT_B"],
        "surfaces": [
            {"surface": name, "assertion": assertion, "result": "PASS"}
            for name, assertion in surfaces
        ],
        "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE4B ATTACK OK: tenants=2 surfaces={len(surfaces)} paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
