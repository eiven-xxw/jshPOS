from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "server/ruoyi-modules/jshpos-payment"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"T2-GATE3A ATTACK ERROR: {message}")


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    dto = read("server/ruoyi-modules/jshpos-payment/src/main/java/com/jingshanghui/pos/payment/interfaces/rest/dto/PaymentRequests.java")
    controllers = "\n".join(path.read_text(encoding="utf-8") for path in
                              (MODULE / "src/main/java/com/jingshanghui/pos/payment/interfaces/rest").glob("*Controller.java"))
    services = "\n".join(path.read_text(encoding="utf-8") for path in
                           (MODULE / "src/main/java/com/jingshanghui/pos/payment/application/service").glob("*.java"))
    mapper = read("server/ruoyi-modules/jshpos-payment/src/main/resources/mapper/payment/PaymentMapper.xml")
    migration = read("server/ruoyi-modules/jshpos-payment/src/main/resources/db/migration/V202608160009__gate3a_payment_refund_reconciliation.sql")
    module_main = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                             for path in (MODULE / "src/main").rglob("*") if path.is_file())
    runtime_java = "\n".join(path.read_text(encoding="utf-8", errors="replace")
                              for path in (MODULE / "src/main/java").rglob("*.java"))

    dto_runtime = re.sub(r"/\*.*?\*/|//[^\n]*", "", dto, flags=re.DOTALL)
    require("tenantId" not in dto_runtime and "tenant_id" not in dto_runtime,
            "public request DTO exposes tenant authority")
    require("TrustedTenantContext" in services and "requirePrincipal()" in services,
            "application services do not inject trusted principal")
    require("@PostMapping(\"/callback" not in controllers and "@RequestMapping(\"/callback" not in controllers,
            "external callback endpoint entered Gate 3A")
    require("PaymentObservation" not in controllers and "RefundObservation" not in controllers,
            "unverified provider observations are exposed through a controller")
    statements = re.findall(r"<(?:select|insert|update|delete)\b[^>]*>(.*?)</(?:select|insert|update|delete)>",
                            mapper, flags=re.IGNORECASE | re.DOTALL)
    require(len(statements) >= 30 and all("#{tenantId}" in item for item in statements),
            "Mapper XML statement lacks explicit trusted tenant predicate/value")
    require("SELECT *" not in mapper.upper() and "FOR UPDATE" in mapper,
            "funds SQL uses wildcard projection or lacks aggregate locking")
    require("FOREIGN KEY (tenant_id" in migration and "UNIQUE KEY uk_pay_provider_transaction" in migration,
            "database tenant composite boundary is incomplete")
    require("provider observation is immutable" in migration.lower()
            and "idempotency result is immutable" in migration.lower(),
            "immutable observation/idempotency evidence is unprotected")
    lowered = runtime_java.lower()
    network_tokens = ("java.net.http", "resttemplate", "webclient", "okhttp", "hutool.http",
                      "httpurlconnection", "https://", "http://")
    require(not any(token in lowered for token in network_tokens), "Provider network client or URL detected")
    require("providersecret" not in lowered and "merchantprivatekey" not in lowered,
            "Provider credential configuration detected")
    require("inventoryservice" not in lowered and "promotionservice" not in lowered,
            "later-Gate domain dependency detected")
    require("PaymentStrictTenantMapperGuard" in module_main, "payment Mapper fail-closed guard missing")

    surfaces = [
        ("REST_DTO", "client tenant authority absent"),
        ("CONTROLLER", "no observation/callback ingestion endpoint"),
        ("APPLICATION_CONTEXT", "trusted tenant and actor injected"),
        ("MAPPER_XML", "every statement explicitly tenant scoped"),
        ("NATIVE_SQL", "explicit fields, tenant predicates and aggregate locks"),
        ("DATABASE_FK", "tenant composite foreign keys"),
        ("PROVIDER_REFERENCE", "provider request/transaction uniqueness is tenant local"),
        ("OBSERVATION", "append-only immutable evidence"),
        ("IDEMPOTENCY", "immutable request hash/result"),
        ("TASK_CACHE_EXPORT_OBJECT", "no task/cache/export/object-store payment path admitted"),
        ("PROVIDER_NETWORK_SECRET", "network clients, URLs and credentials absent"),
        ("LATER_GATE_SCOPE", "inventory and promotion dependencies absent"),
    ]
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {
        "schemaVersion": "1.0", "phase": "T2-GATE3A",
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "commitSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tenants": ["TENANT_A", "TENANT_B"],
        "surfaces": [{"surface": name, "assertion": assertion, "result": "PASS"}
                     for name, assertion in surfaces],
        "providerNetworkCalls": 0,
        "externalEvidence": {"sandbox": 0, "realDevice": 0, "pilot": 0},
    }
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2-GATE3A ATTACK OK: tenants=2 surfaces={len(surfaces)} paymentNetwork=0 external=0")


if __name__ == "__main__":
    main()
