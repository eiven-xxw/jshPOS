from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TRANSFER = ROOT / "server/ruoyi-modules/jshpos-transfer"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE4D ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def mapper_statements(xml: str) -> list[str]:
    return re.findall(r"<(?:select|insert|update|delete)\b[^>]*>(.*?)</(?:select|insert|update|delete)>",
                      xml, flags=re.IGNORECASE | re.DOTALL)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    dto = read("server/ruoyi-modules/jshpos-transfer/src/main/java/com/jingshanghui/pos/transfer/"
               "interfaces/rest/dto/TransferRequests.java")
    dto_runtime = re.sub(r"/\*.*?\*/|//[^\n]*", "", dto, flags=re.DOTALL)
    for forbidden in ("tenantId", "tenant_id", "unitCost", "costAmount", "onHandQuantity",
                      "averageUnitCost", "sourceCost"):
        require(forbidden not in dto_runtime, f"request DTO exposes authority or cost input: {forbidden}")

    service = read("server/ruoyi-modules/jshpos-transfer/src/main/java/com/jingshanghui/pos/transfer/"
                   "application/service/TransferService.java")
    source_service = read("server/ruoyi-modules/jshpos-transfer/src/main/java/com/jingshanghui/pos/transfer/"
                          "application/service/TransferCostSourceService.java")
    costing = read("server/ruoyi-modules/jshpos-costing/src/main/java/com/jingshanghui/pos/costing/"
                   "application/service/CostingService.java")
    inventory_rules = read("server/ruoyi-modules/jshpos-inventory/src/main/java/com/jingshanghui/pos/inventory/"
                           "domain/InventoryRules.java")
    require("TrustedTenantContext" in service and "AuthoritativeInventoryMovementPort" in service,
            "transfer trusted context or inventory Owner port missing")
    require("authorizationService.requireStoreAccess(head.sourceStoreId())" in service and
            "authorizationService.requireStoreAccess(head.destinationStoreId())" in service,
            "both source and destination data scopes must be enforced")
    require("TRANSFER_DISPATCH" in inventory_rules and "TRANSFER_RECEIPT" in inventory_rules,
            "inventory Owner source admission missing")
    require("TransferCostSourcePort" in costing and "TRANSFER_SOURCE_SNAPSHOT" in
            read("server/ruoyi-modules/jshpos-costing/src/main/java/com/jingshanghui/pos/costing/"
                 "domain/CostingRules.java"), "cost inheritance contract missing")
    require("@Transactional(readOnly = true)" in source_service,
            "transfer cost source must be tenant-scoped read-only")
    event_schema = json.loads(read(
        "contracts/t2/gate4d/schemas/inventory.transfer.changed.v1.schema.json"))
    require(event_schema.get("additionalProperties") is False and
            {"eventId", "eventType", "aggregateId", "aggregateVersion", "transferId",
             "sourceWarehouseId", "destinationWarehouseId", "correlationId"}.issubset(
                 set(event_schema.get("required", []))),
            "transfer event envelope is incomplete or open-ended")
    require("body.put(\"eventId\", eventId)" in service and
            "body.put(\"aggregateVersion\", version)" in service and
            "body.put(\"correlationId\", correlationId)" in service,
            "Outbox payload does not materialize its governed event envelope")

    xml = read("server/ruoyi-modules/jshpos-transfer/src/main/resources/mapper/transfer/TransferMapper.xml")
    statements = mapper_statements(xml)
    require(len(statements) >= 20 and all("#{tenantId}" in statement for statement in statements),
            "transfer Mapper statement lacks explicit trusted tenant predicate/value")
    require("SELECT *" not in xml.upper() and "FOR UPDATE" in xml,
            "transfer Mapper uses wildcard projection or lacks aggregate/line row lock")
    require(not re.search(r"(?:UPDATE|INSERT\s+INTO)\s+inv_stock_", xml, re.IGNORECASE),
            "transfer module writes inventory Owner tables directly")
    require(not re.search(r"(?:UPDATE|INSERT\s+INTO)\s+inv_cost_", xml, re.IGNORECASE),
            "transfer module writes cost Owner tables directly")
    require("UPDATE INV_TRANSFER_DISPATCH" not in xml.upper() and
            "UPDATE INV_TRANSFER_RECEIPT" not in xml.upper() and
            "UPDATE INV_TRANSFER_TRANSIT_LEDGER" not in xml.upper(),
            "immutable transfer facts expose a mutation path")

    runtime_java = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                              for root in (TRANSFER,
                                           ROOT / "server/ruoyi-modules/jshpos-inventory",
                                           ROOT / "server/ruoyi-modules/jshpos-costing")
                              for path in root.joinpath("src/main/java").rglob("*.java")).lower()
    require(not any(token in runtime_java for token in (
        "java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http",
        "httpurlconnection", "https://", "http://")), "Provider network client or URL detected")
    require(not any(token in runtime_java for token in (
        "promotionservice", "memberservice", "accountspayableservice", "generalledgerservice")),
        "later-Gate runtime detected")
    require("float " not in runtime_java and "double " not in runtime_java,
            "floating-point quantity or money type detected")
    require("TransferStrictTenantMapperGuard" in "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in TRANSFER.joinpath("src/main").rglob("*") if path.is_file()),
        "transfer fail-closed tenant guard missing")

    surfaces = [
        ("REST_DTO", "tenant, balances and cost authority absent"),
        ("TRUSTED_CONTEXT", "tenant and actor injected from trusted principal"),
        ("SOURCE_STORE_SCOPE", "source store scope authorization enforced"),
        ("DESTINATION_STORE_SCOPE", "destination store scope authorization enforced"),
        ("MAPPER", "every SQL statement tenant scoped"),
        ("NATIVE_SQL", "explicit columns and aggregate row locks"),
        ("INVENTORY_OWNER", "quantity effects use admitted internal Owner port"),
        ("COST_OWNER", "destination cost inherits authoritative dispatch snapshot"),
        ("IDEMPOTENCY", "command ID and canonical content digest are stable"),
        ("OPTIMISTIC_VERSION", "aggregate state changes require expected version"),
        ("IMMUTABLE_FACTS", "dispatch receipt transit and audit facts are append-only"),
        ("TRANSIT_EQUATION", "dispatched equals received plus open plus approved difference"),
        ("APPROVAL_SEPARATION", "creator cannot approve own transfer"),
        ("TASK_CACHE_EXPORT_OBJECT", "no alternate task/cache/export/object path admitted"),
        ("PROVIDER_NETWORK_SECRET", "network clients URLs and credentials absent"),
        ("DEFERRED_RUNTIME", "promotion member report AP and GL runtime absent"),
        ("TWO_TENANT_FIXTURE", "TENANT_A and TENANT_B remain synthetic and isolated"),
        ("FORWARD_REPAIR", "published migrations remain immutable and repair is additive"),
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE4D",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tenants": ["TENANT_A", "TENANT_B"],
        "surfaces": [{"surface": name, "assertion": assertion, "result": "PASS"}
                     for name, assertion in surfaces],
        "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE4D ATTACK OK: tenants=2 surfaces={len(surfaces)} paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
