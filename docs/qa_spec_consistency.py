from __future__ import annotations

import json
import re
import sys
from pathlib import Path


BASE_DIR = Path(__file__).with_name("详细设计")
SPECS = {
    "31": BASE_DIR / "31_领域模型与数据库设计说明书_V1.0.md",
    "32": BASE_DIR / "32_订单支付退款状态机规格_V1.0.md",
    "33": BASE_DIR / "33_库存账本预占与成本核算规格_V1.0.md",
    "34": BASE_DIR / "34_促销规则与优惠分摊规格_V1.0.md",
    "35": BASE_DIR / "35_POS离线同步协议_V1.0.md",
    "36": BASE_DIR / "36_Android设备适配协议与硬件认证手册_V1.0.md",
    "37": BASE_DIR / "37_连接器SDK与标准数据契约_V1.0.md",
    "38": BASE_DIR / "38_开放平台API与Webhook规范_V1.0.md",
    "39": BASE_DIR / "39_安全隐私等保与灾备实施方案_V1.0.md",
    "40": BASE_DIR / "40_商业V1验收测试计划_V1.0.md",
}

GLOBAL_REQUIRED = ["tenant_id", "VARCHAR(20)", "ULID"]

PER_SPEC_REQUIRED = {
    "31": [
        "BIGINT",
        "DECIMAL(19,6)",
        "Outbox",
        "Inbox",
        "record_version",
    ],
    "32": [
        "DRAFT",
        "PENDING_PAYMENT",
        "CONFIRMED",
        "COMPLETED",
        "UNKNOWN",
        "payment.succeeded.v1",
        "refund.succeeded.v1",
        "idempotency_key",
        "Outbox",
        "Inbox",
    ],
    "33": [
        "DECIMAL(19,6)",
        "on_hand_qty",
        "reserved_qty",
        "available_to_sell_qty",
        "SALE_OUT",
        "SALE_RETURN_IN",
        "stock_command_id",
        "Outbox",
        "Inbox",
    ],
    "34": [
        "BIGINT",
        "DECIMAL(19,6)",
        "最大余数法",
        "promotion_fingerprint",
        "engine_version",
        "payload_hash",
        "Outbox",
    ],
    "35": [
        "SQLite",
        "WAL",
        "payload_hash",
        "device_sequence",
        "ACCEPTED_PENDING",
        "DUPLICATE",
        "CONFLICT",
        "Outbox",
        "Inbox",
    ],
    "36": [
        "Flutter",
        "Kotlin",
        "Device Gateway",
        "Adapter",
        "receipt_print.v1",
        "UNKNOWN",
        "idempotency_key",
        "硬件认证体系",
    ],
    "37": [
        "CloudEvents",
        "JSON Schema",
        "Capability Manifest",
        "Money",
        "Quantity",
        "idempotency_key",
        "payload_hash",
        "Outbox",
        "Inbox",
        "鲸熵汇",
    ],
    "38": [
        "OpenAPI 3.1.2",
        "RFC 9457",
        "RFC 9700",
        "RFC 9421",
        "RFC 9530",
        "Idempotency-Key",
        "If-Match",
        "Content-Digest",
        "Outbox",
    ],
    "39": [
        "GB/T 22239-2019",
        "ASVS 5.0",
        "PCI DSS 4.0.1",
        "KMS",
        "Outbox/Inbox",
        "RTO",
        "RPO",
        "30 分钟 / 5 分钟",
        "4 小时 / 1 小时",
    ],
    "40": [
        "RTM",
        "P0/P1",
        "Go/No-Go",
        "Outbox/Inbox",
        "UNKNOWN",
        "最大余数法",
        "L2",
        "RTO",
        "RPO",
        "7—14 天",
    ],
}

FORBIDDEN_PATTERNS = {
    "tenant_id_as_bigint": re.compile(
        r"tenant_id\s*(?:字段|类型|为|是|采用|使用|:|：)?\s*BIGINT",
        re.IGNORECASE,
    ),
    "money_float_field": re.compile(
        r"(?:amount|金额|应收|实收|退款|优惠)[^\n]{0,40}"
        r"(?:FLOAT|DOUBLE|JavaScript\s+Number)\s*(?:存储|字段|计算类型|类型)",
        re.IGNORECASE,
    ),
    "network_exactly_once_claim": re.compile(
        r"(?:网络|传输)[^\n]{0,20}(?:本身)?\s*(?:是|采用|保证)\s*"
        r"(?:exactly[- ]once|恰好一次)",
        re.IGNORECASE,
    ),
}


def heading_set(text: str) -> set[str]:
    return {
        match.group(1).strip()
        for match in re.finditer(r"^#{1,6}\s+(.+?)\s*$", text, re.MULTILINE)
    }


def main() -> None:
    report: dict[str, object] = {
        "files": {},
        "cross_document": {},
        "violations": [],
    }
    texts: dict[str, str] = {}

    for number, path in SPECS.items():
        if not path.exists():
            report["violations"].append(f"{number}: missing file {path}")
            continue
        file_text = path.read_text(encoding="utf-8")
        texts[number] = file_text
        missing = [
            token
            for token in GLOBAL_REQUIRED + PER_SPEC_REQUIRED[number]
            if token not in file_text
        ]
        forbidden = [
            name
            for name, pattern in FORBIDDEN_PATTERNS.items()
            if pattern.search(file_text)
        ]
        bad_replacement = "\ufffd" in file_text
        file_report = {
            "path": str(path),
            "bytes": path.stat().st_size,
            "lines": file_text.count("\n") + 1,
            "headings": len(heading_set(file_text)),
            "missing_required_tokens": missing,
            "forbidden_patterns": forbidden,
            "replacement_character": bad_replacement,
        }
        report["files"][number] = file_report
        if missing:
            report["violations"].append(f"{number}: missing {missing}")
        if forbidden:
            report["violations"].append(f"{number}: forbidden {forbidden}")
        if bad_replacement:
            report["violations"].append(f"{number}: contains U+FFFD")

    if all(number in texts for number in SPECS):
        cross = {
            "tenant_id_type_consistent": all(
                "tenant_id" in file_text and "VARCHAR(20)" in file_text
                for file_text in texts.values()
            ),
            "order_events_referenced": all(
                event in texts["32"]
                for event in (
                    "order.confirmed.v1",
                    "payment.succeeded.v1",
                    "refund.succeeded.v1",
                )
            ),
            "inventory_movement_consistent": all(
                movement in texts["33"]
                for movement in ("SALE_OUT", "SALE_RETURN_IN")
            )
            and all(
                movement in texts["32"]
                for movement in ("SALE_OUT", "SALE_RETURN_IN")
            ),
            "promotion_return_snapshot_consistent": all(
                token in texts["34"]
                for token in ("原成交", "allocation", "不重新运行当前促销")
            )
            and "原订单" in texts["32"],
            "offline_idempotency_consistent": all(
                token in texts["35"]
                for token in (
                    "event_id",
                    "command_id",
                    "payload_hash",
                    "Outbox",
                    "Inbox",
                )
            ),
            "connector_contract_consistent": all(
                token in texts["37"]
                for token in (
                    "tenant_id",
                    "VARCHAR(20)",
                    "ULID",
                    "Money",
                    "Quantity",
                    "Outbox",
                    "Inbox",
                )
            ),
            "open_platform_contract_consistent": all(
                token in texts["38"]
                for token in (
                    "tenant_id",
                    "VARCHAR(20)",
                    "ULID",
                    "amount_minor",
                    "Idempotency-Key",
                    "If-Match",
                )
            ),
            "connector_openapi_boundary_consistent": (
                "遵循 POS-DD-038" in texts["37"]
                and "连接器" in texts["38"]
            ),
            "security_dr_targets_consistent": all(
                token in texts["39"] and token in texts["40"]
                for token in ("30 分钟", "5 分钟", "4 小时", "1 小时")
            ),
            "acceptance_traceability_complete": all(
                token in texts["40"]
                for token in (
                    "POS-DD-031",
                    "POS-DD-039",
                    "订单、支付与退款",
                    "库存与成本",
                    "POS 离线同步",
                    "安全与隐私",
                )
            ),
        }
        report["cross_document"] = cross
        for name, ok in cross.items():
            if not ok:
                report["violations"].append(f"cross-document: {name} failed")

    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report["violations"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
