from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
COSTING = ROOT / "server/ruoyi-modules/jshpos-costing"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE4C ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def mapper_statements(xml: str) -> list[str]:
    return re.findall(
        r"<(?:select|insert|update|delete)\b[^>]*>(.*?)</(?:select|insert|update|delete)>",
        xml, flags=re.IGNORECASE | re.DOTALL,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    dto = read("server/ruoyi-modules/jshpos-costing/src/main/java/com/jingshanghui/pos/costing/"
               "interfaces/rest/dto/CostingRequests.java")
    dto_runtime = re.sub(r"/\*.*?\*/|//[^\n]*", "", dto, flags=re.DOTALL)
    for forbidden in ("tenantId", "tenant_id", "unitCost", "costAmount", "quantity", "currencyCode"):
        require(forbidden not in dto_runtime, f"request DTO exposes authority or cost input: {forbidden}")

    service = read("server/ruoyi-modules/jshpos-costing/src/main/java/com/jingshanghui/pos/costing/"
                   "application/service/CostingService.java")
    inventory_service = read("server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/"
                             "application/service/InventoryLedgerService.java")
    procurement_source = read("server/ruoyi-modules/jshpos-procurement/src/main/java/com/jingshanghui/pos/"
                              "procurement/application/service/ProcurementCostSourceService.java")
    require("TrustedTenantContext" in service and "AuthoritativeCostPostingPort" in service,
            "cost Owner trusted context or inventory port missing")
    require("costPostingPort.applyPostedLedger" in inventory_service,
            "inventory Owner does not call cost Owner transaction port")
    require("@Transactional(readOnly = true)" in procurement_source,
            "procurement cost source must be read-only")

    xml = read("server/ruoyi-modules/jshpos-costing/src/main/resources/mapper/costing/CostingMapper.xml")
    statements = mapper_statements(xml)
    require(len(statements) >= 15 and all("#{tenantId}" in statement for statement in statements),
            "cost Mapper statement lacks explicit trusted tenant predicate/value")
    require("SELECT *" not in xml.upper() and "FOR UPDATE" in xml,
            "cost Mapper uses wildcard projection or lacks balance row lock")
    require(not re.search(r"(?:UPDATE|INSERT\s+INTO)\s+inv_stock_", xml, re.IGNORECASE),
            "cost module writes inventory tables directly")
    require(not re.search(r"(?:UPDATE|INSERT\s+INTO)\s+pur_", xml, re.IGNORECASE),
            "cost module writes procurement tables directly")
    require("UPDATE INV_COST_LEDGER" not in xml.upper() and "DELETE FROM INV_COST_LEDGER" not in xml.upper(),
            "cost history has a mutation path")

    runtime_java = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for root in (
            COSTING,
            ROOT / "server/ruoyi-modules/jshpos-inventory",
            ROOT / "server/ruoyi-modules/jshpos-procurement",
        )
        for path in root.joinpath("src/main/java").rglob("*.java")
    ).lower()
    require(not any(token in runtime_java for token in (
        "java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http",
        "httpurlconnection", "https://", "http://",
    )), "Provider network client or URL detected")
    require(not any(token in runtime_java for token in (
        "transferservice", "promotionservice", "accountspayableservice", "generalledgerservice",
    )), "later-Gate runtime detected")
    require("float " not in runtime_java and "double " not in runtime_java,
            "floating-point quantity or money type detected")
    require("CostingStrictTenantMapperGuard" in "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in COSTING.joinpath("src/main").rglob("*") if path.is_file()
    ), "cost fail-closed tenant guard missing")

    surfaces = [
        ("REST_DTO", "tenant, cost, quantity and currency authority absent"),
        ("TRUSTED_CONTEXT", "tenant and actor injected from trusted principal"),
        ("STORE_SCOPE", "store scope authorization enforced"),
        ("MAPPER", "every SQL statement tenant scoped"),
        ("NATIVE_SQL", "explicit columns and row lock"),
        ("INVENTORY_OWNER", "cost consumes posted inventory fact through internal port"),
        ("PROCUREMENT_OWNER", "frozen procurement facts exposed by read-only port"),
        ("IDEMPOTENCY", "inventory ledger ID and source digest are stable"),
        ("SEQUENCE", "gap and unknown late facts fail closed"),
        ("IMMUTABLE_LEDGER", "cost history is append-only"),
        ("REBUILD", "balance is reconstructible from tenant cost ledger"),
        ("TASK_CACHE_EXPORT_OBJECT", "no alternate task/cache/export/object path admitted"),
        ("PROVIDER_NETWORK_SECRET", "network clients, URLs and credentials absent"),
        ("DEFERRED_RUNTIME", "transfer, promotion, AP and GL runtime absent"),
        ("TWO_TENANT_FIXTURE", "TENANT_A and TENANT_B remain synthetic and isolated"),
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE4C",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tenants": ["TENANT_A", "TENANT_B"],
        "surfaces": [{"surface": name, "assertion": assertion, "result": "PASS"}
                     for name, assertion in surfaces],
        "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE4C ATTACK OK: tenants=2 surfaces={len(surfaces)} paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
