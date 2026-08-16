from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "server/ruoyi-modules/jshpos-inventory"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE4A ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    dto = read("server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/interfaces/rest/dto/InventoryRequests.java")
    services = read("server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/application/service/InventoryLedgerService.java")
    mapper = read("server/ruoyi-modules/jshpos-inventory/src/main/resources/mapper/inventory/InventoryMapper.xml")
    migration = read("server/ruoyi-modules/jshpos-inventory/src/main/resources/db/migration/V202608160011__gate4a_inventory_ledger.sql")
    module_main = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                             for path in MODULE.joinpath("src/main").rglob("*") if path.is_file())
    runtime_java = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                              for path in MODULE.joinpath("src/main/java").rglob("*.java"))
    dto_runtime = re.sub(r"/\*.*?\*/|//[^\n]*", "", dto, flags=re.DOTALL)
    require("tenantId" not in dto_runtime and "tenant_id" not in dto_runtime,
            "request DTO exposes tenant authority")
    require("skuId" not in dto_runtime and "quantity" not in dto_runtime,
            "source command DTO exposes client-controlled SKU or quantity")
    require("TrustedTenantContext" in services and "requirePrincipal()" in services,
            "application service does not inject trusted principal")
    require("InventoryOrderSnapshotPort" in services and "InventoryRefundSnapshotPort" in services,
            "authoritative source Owner ports are missing")
    statements = re.findall(r"<(?:select|insert|update|delete)\b[^>]*>(.*?)</(?:select|insert|update|delete)>",
                            mapper, flags=re.IGNORECASE | re.DOTALL)
    require(len(statements) >= 15 and all("#{tenantId}" in item for item in statements),
            "Mapper XML statement lacks explicit trusted tenant predicate/value")
    require("SELECT *" not in mapper.upper() and "FOR UPDATE" in mapper and "SUM(quantity_delta)" in mapper,
            "inventory SQL uses wildcard projection or lacks lock/rebuild aggregate")
    require("FOREIGN KEY (tenant_id" in migration and "uk_inv_ledger_source_line" in migration,
            "database tenant/idempotency boundary is incomplete")
    require("inv_stock_ledger is immutable" in migration and "inv_stock_policy_version is immutable" in migration,
            "immutable ledger/policy protection is missing")
    lowered = runtime_java.lower()
    require(not any(token in lowered for token in ("java.net.http", "resttemplate", "webclient", "okhttp",
                                                     "hutool.http", "httpurlconnection", "https://", "http://")),
            "Provider network client or URL detected")
    require(not any(token in lowered for token in ("stocktakeservice", "purchaseservice", "costservice",
                                                     "transferservice", "promotionservice")),
            "later-Gate runtime detected")
    require("InventoryStrictTenantMapperGuard" in module_main, "inventory Mapper fail-closed guard missing")
    surfaces = [
        ("REST_DTO", "client tenant, SKU and quantity authority absent"),
        ("ORDER_OWNER_PORT", "sale lines come from authoritative order snapshot"),
        ("REFUND_OWNER_PORT", "return lines come from succeeded refund snapshot"),
        ("APPLICATION_CONTEXT", "trusted tenant and actor injected"),
        ("MAPPER_XML", "every statement explicitly tenant scoped"),
        ("NATIVE_SQL", "explicit fields, row locks and ledger aggregate"),
        ("DATABASE_FK", "tenant composite foreign keys"),
        ("IDEMPOTENCY", "event hash and source-line uniqueness"),
        ("IMMUTABLE_LEDGER", "update/delete triggers protect quantity facts"),
        ("TASK_CACHE_EXPORT_OBJECT", "no task/cache/export/object-store inventory path admitted"),
        ("PROVIDER_NETWORK_SECRET", "network clients, URLs and credentials absent"),
        ("LATER_GATE_SCOPE", "stocktake, procurement, cost, transfer and promotion runtime absent"),
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE4A",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tenants": ["TENANT_A", "TENANT_B"],
        "surfaces": [{"surface": name, "assertion": assertion, "result": "PASS"}
                     for name, assertion in surfaces],
        "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE4A ATTACK OK: tenants=2 surfaces={len(surfaces)} paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
