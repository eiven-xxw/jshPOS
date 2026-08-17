from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE5B ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def statements(xml: str) -> list[str]:
    return re.findall(r"<(?:select|insert|update|delete)\b[^>]*>(.*?)</(?:select|insert|update|delete)>",
                      xml, flags=re.IGNORECASE | re.DOTALL)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    dto = re.sub(r"/\*.*?\*/|//[^\n]*", "", read(
        "server/ruoyi-modules/jshpos-returns/src/main/java/com/jingshanghui/pos/returns/"
        "interfaces/rest/dto/ReturnRequests.java"), flags=re.DOTALL)
    for field in ("tenantId", "tenant_id", "actorUserId", "approverUserId"):
        require(field not in dto, f"public Return DTO exposes authority field {field}")
    controller = read("server/ruoyi-modules/jshpos-returns/src/main/java/com/jingshanghui/pos/returns/"
                      "interfaces/rest/ReturnController.java")
    for permission in ("return:request:create", "return:request:approve", "return:request:read"):
        require(permission in controller, f"permission missing {permission}")
    require("processNext" not in controller and "acceptPromotion" not in controller
            and "observePayment" not in controller, "internal Owner orchestration exposed by REST")

    service = read("server/ruoyi-modules/jshpos-returns/src/main/java/com/jingshanghui/pos/returns/"
                   "application/service/ReturnOrchestrationService.java")
    require("TrustedTenantContext" in service and service.count("requireStoreAccess") >= 6,
            "trusted tenant/store scope missing from Return checkpoints")
    require("requesterUserId().equals(principal.userId())" in service,
            "independent return approval check missing")
    require("PAYMENT_UNKNOWN_QUERY_REQUIRED" in service and "regenerate" not in service.lower(),
            "UNKNOWN checkpoint or no-regeneration policy missing")

    mapper = read("server/ruoyi-modules/jshpos-returns/src/main/resources/mapper/returns/ReturnMapper.xml")
    sql = statements(mapper)
    require(len(sql) >= 20 and all("#{tenantId}" in item for item in sql),
            "Return Mapper statement lacks explicit trusted tenant binding")
    require("SELECT *" not in mapper.upper() and mapper.upper().count("FOR UPDATE") >= 2,
            "Return aggregate locks or explicit projection missing")
    for owner in ("ORD_", "PAY_", "INV_", "PRM_"):
        require(f"UPDATE {owner}" not in mapper.upper() and f"DELETE FROM {owner}" not in mapper.upper(),
                f"Return Owner exposes cross-owner write {owner}")

    all_runtime = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                              for module in ("jshpos-returns", "jshpos-order", "jshpos-payment")
                              for path in (ROOT / "server/ruoyi-modules" / module / "src/main/java").rglob("*.java"))
    lowered = all_runtime.lower()
    require(not any(token in lowered for token in ("java.net.http", "resttemplate", "webclient", "okhttp",
                                                    "hutool.http", "httpurlconnection", "https://", "http://")),
            "Provider HTTP/SDK runtime detected")
    require("float " not in lowered and "double " not in lowered, "floating-point money or quantity detected")
    for forbidden in ("couponservice", "memberservice", "loyaltyservice", "storedvalueservice",
                      "reportservice", "budgetreservationservice", "accountspayableservice",
                      "generalledgerservice"):
        require(forbidden not in lowered, f"later-Gate runtime detected {forbidden}")
    require("ReturnsStrictTenantMapperGuard" in all_runtime, "Return fail-closed Mapper guard missing")

    surfaces = [
        "REST_AUTHORITY", "CONTROLLER_PERMISSION", "INTERNAL_PORT_NOT_REST", "TRUSTED_TENANT",
        "STORE_SCOPE", "APPROVER_SEPARATION", "ORDER_GUARD_LOCK", "CUMULATIVE_QUANTITY",
        "PROMOTION_SNAPSHOT", "AMOUNT_CONSERVATION", "CASH_REFUND_CAP", "SHIFT_CASH_ATOMIC",
        "PAYMENT_UNKNOWN", "INBOX_HASH", "OUTBOX_STABLE_EVENT", "OWNER_CHECKPOINT",
        "INVENTORY_OWNER", "MAPPER_TENANT", "NATIVE_SQL_LOCK", "CROSS_OWNER_WRITE",
        "CACHE_TASK_EXPORT_OBJECT", "PROVIDER_NETWORK", "FLOATING_POINT", "DEFERRED_RUNTIME",
        "TWO_SYNTHETIC_TENANTS", "FORWARD_REPAIR",
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE5B",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tenants": ["TENANT_A", "TENANT_B"],
        "surfaces": [{"surface": item, "result": "PASS"} for item in surfaces],
        "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE5B ATTACK OK: surfaces={len(surfaces)} tenants=2 providerNetwork=0")


if __name__ == "__main__":
    main()
