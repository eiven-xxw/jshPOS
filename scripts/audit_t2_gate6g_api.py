#!/usr/bin/env python3
"""审计 Gate 6G 正式 REST/OpenAPI、权限、租户和错误码契约。"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HTTP_METHODS = {"get", "post", "put", "delete", "patch"}
OPENAPI_EXCLUDES = ("draft", "design")
PERMISSION_EXCEPTIONS = {
    ("POST", "/api/pos/v1/terminals/activate"): "一次性激活凭据协议在业务权限建立前完成认证",
}
ERROR_PATTERN = re.compile(r"^[A-Z][A-Z0-9]{1,15}-(?:[0-9]{3}|[A-Z][A-Z0-9-]{1,63})$")


def normalize_path(value: str) -> str:
    value = re.sub(r"/+", "/", value.strip())
    if not value.startswith("/"):
        value = "/" + value
    if len(value) > 1:
        value = value.rstrip("/")
    return value


def join_path(prefix: str, path: str) -> str:
    return normalize_path(prefix.rstrip("/") + "/" + path.lstrip("/"))


def controller_operations() -> tuple[dict[tuple[str, str], dict], list[dict]]:
    operations: dict[tuple[str, str], dict] = {}
    permission_exceptions: list[dict] = []
    root = ROOT / "server" / "ruoyi-modules"
    for source in sorted(root.glob("jshpos-*/src/main/java/**/*Controller.java")):
        text = source.read_text(encoding="utf-8")
        if "@RestController" not in text:
            continue
        class_area = text[: text.find("public class") if "public class" in text else len(text)]
        class_match = re.search(r'@RequestMapping\(\s*"([^"]*)"', class_area)
        prefix = class_match.group(1) if class_match else ""
        token = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\b")
        for match in token.finditer(text):
            line_end = text.find("\n", match.end())
            line_end = len(text) if line_end < 0 else line_end
            annotation_line = text[match.start() : line_end]
            path_match = re.search(r'"([^"]*)"', annotation_line)
            path = join_path(prefix, path_match.group(1) if path_match else "")
            method = match.group(1).upper()
            expanded = [(method, path)]
            action = re.search(r"\{action:([^}]+)\}", path)
            if action:
                expanded = [
                    (method, path[: action.start()] + choice + path[action.end() :])
                    for choice in action.group(1).split("|")
                ]
            method_index = text.find("public ", match.end())
            annotation_area = text[match.end() : method_index if method_index >= 0 else match.end() + 1000]
            permission_match = re.search(r'@SaCheckPermission\("([^"]+)"', annotation_line + annotation_area)
            permission = permission_match.group(1) if permission_match else None
            for key in expanded:
                if key in operations:
                    raise AssertionError(f"重复 Controller 路由: {key}")
                record = {
                    "method": key[0],
                    "path": normalize_path(key[1]),
                    "permission": permission,
                    "source": source.relative_to(ROOT).as_posix(),
                }
                operations[key] = record
                if permission is None:
                    reason = PERMISSION_EXCEPTIONS.get(key)
                    if reason:
                        permission_exceptions.append({**record, "reason": reason})
                    else:
                        record["permissionFailure"] = True
    return operations, permission_exceptions


def selected_openapi_files() -> list[Path]:
    candidates = sorted((ROOT / "contracts" / "t2").glob("**/openapi-*.yaml"))
    return [item for item in candidates if not any(flag in item.name.lower() for flag in OPENAPI_EXCLUDES)]


def openapi_operations() -> tuple[dict[tuple[str, str], dict], list[dict], list[str]]:
    operations: dict[tuple[str, str], dict] = {}
    operation_ids: defaultdict[str, list[str]] = defaultdict(list)
    files: list[str] = []
    for source in selected_openapi_files():
        text = source.read_text(encoding="utf-8")
        relative = source.relative_to(ROOT).as_posix()
        files.append(relative)
        server_match = re.search(r"^servers:\s*\n\s*-\s*url:\s*([^\s#]+)", text, re.MULTILINE)
        server = server_match.group(1).strip("'\"") if server_match else ""
        lines = text.splitlines()
        current_path: str | None = None
        for index, line in enumerate(lines):
            path_match = re.match(r"^  (/.*):\s*$", line)
            if path_match:
                current_path = path_match.group(1)
                continue
            method_match = re.match(r"^    (get|post|put|delete|patch):", line)
            if not method_match or current_path is None:
                continue
            method = method_match.group(1).upper()
            path = join_path(server, current_path)
            key = (method, path)
            if key in operations:
                raise AssertionError(f"重复 OpenAPI 路由: {key} ({relative})")
            window = "\n".join(lines[index : index + 12])
            operation_id_match = re.search(r"operationId:\s*([A-Za-z][A-Za-z0-9_-]*)", window)
            operation_id = operation_id_match.group(1) if operation_id_match else None
            record = {"method": method, "path": path, "operationId": operation_id, "source": relative}
            operations[key] = record
            if operation_id:
                operation_ids[operation_id].append(f"{method} {path}")
    duplicate_ids = [
        {"operationId": key, "operations": value}
        for key, value in sorted(operation_ids.items())
        if len(value) > 1
    ]
    return operations, duplicate_ids, files


def error_catalog() -> tuple[list[dict], list[dict]]:
    catalog: defaultdict[str, dict] = defaultdict(lambda: {"messages": set(), "sources": set()})
    invalid: list[dict] = []
    roots = [
        *sorted((ROOT / "server" / "ruoyi-modules").glob("jshpos-*/src/main/java")),
        ROOT / "admin-web" / "src" / "api",
        ROOT / "pos-flutter" / "lib",
    ]
    literal = re.compile(r'["\']([A-Z][A-Z0-9-]{2,}):\s*([^"\'\r\n]*)')
    for root in roots:
        if not root.exists():
            continue
        for source in sorted(path for path in root.rglob("*") if path.suffix in {".java", ".ts", ".dart"}):
            text = source.read_text(encoding="utf-8", errors="strict")
            for code, message in literal.findall(text):
                # JSH-MEMBER 是配置 AAD 前缀，不是 API 错误码。
                if code == "JSH-MEMBER":
                    continue
                if not ERROR_PATTERN.fullmatch(code):
                    invalid.append({"code": code, "source": source.relative_to(ROOT).as_posix()})
                    continue
                catalog[code]["messages"].add(message.strip())
                catalog[code]["sources"].add(source.relative_to(ROOT).as_posix())
    rows = [
        {"code": code, "messages": sorted(item["messages"]), "sources": sorted(item["sources"])}
        for code, item in sorted(catalog.items())
    ]
    return rows, invalid


def tenant_override_failures() -> list[str]:
    failures: list[str] = []
    for source in sorted((ROOT / "server" / "ruoyi-modules").glob("jshpos-*/src/main/java/**/interfaces/rest/dto/*.java")):
        text = source.read_text(encoding="utf-8")
        code_only = re.sub(r"/\*[\s\S]*?\*/|//[^\r\n]*", "", text)
        if re.search(r"\btenant_?id\b", code_only, re.IGNORECASE):
            failures.append(source.relative_to(ROOT).as_posix())
    return failures


def event_contract_summary() -> dict:
    files = sorted(
        path.relative_to(ROOT).as_posix()
        for path in (ROOT / "contracts" / "t2").glob("**/*event*")
        if path.is_file() and path.suffix in {".json", ".yaml", ".yml"}
    )
    convention = "contracts/t2/gate6g/event-conventions-v1.json"
    return {
        "count": len(files),
        "files": files,
        "conventionPresent": convention in files,
        "failure": [] if len(files) >= 10 and convention in files else ["事件契约或 Gate6G 事件兼容约定不完整"],
    }


def frontend_contract_summary() -> dict:
    admin_roots = ["foundation", "catalog", "operations", "reporting", "terminal"]
    admin_sources = [ROOT / "admin-web" / "src" / "api" / item / "index.ts" for item in admin_roots]
    admin_call_count = sum(source.read_text(encoding="utf-8").count("request(") for source in admin_sources if source.exists())
    admin_tests = list((ROOT / "admin-web" / "src" / "api").glob("**/__tests__/*.spec.ts"))
    pos_http = list((ROOT / "pos-flutter" / "lib").glob("**/*http*.dart"))
    pos_contract_tests = {
        *list((ROOT / "pos-flutter" / "test").glob("**/*contract*test.dart")),
        *list((ROOT / "pos-flutter" / "test").glob("**/*http*test.dart")),
    }
    failures = []
    if admin_call_count == 0 or not admin_tests:
        failures.append("Vue 正式 API 调用或契约测试缺失")
    if not pos_http or not pos_contract_tests:
        failures.append("Flutter 正式 HTTP 适配器或契约测试缺失")
    return {
        "adminApiCallCount": admin_call_count,
        "adminContractTestCount": len(admin_tests),
        "posHttpAdapterCount": len(pos_http),
        "posContractTestCount": len(pos_contract_tests),
        "failure": failures,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    controllers, permission_exceptions = controller_operations()
    contracts, duplicate_ids, contract_files = openapi_operations()
    errors, invalid_errors = error_catalog()
    events = event_contract_summary()
    frontend = frontend_contract_summary()
    missing_contract = [controllers[key] for key in sorted(controllers.keys() - contracts.keys())]
    orphan_contract = [contracts[key] for key in sorted(contracts.keys() - controllers.keys())]
    missing_operation_id = [item for item in contracts.values() if not item["operationId"]]
    missing_permission = [item for item in controllers.values() if item.get("permissionFailure")]
    tenant_overrides = tenant_override_failures()

    hard_failures = {
        "controllerWithoutOpenApi": missing_contract,
        "openApiWithoutController": orphan_contract,
        "missingOperationId": missing_operation_id,
        "duplicateOperationId": duplicate_ids,
        "missingPermission": missing_permission,
        "requestDtoTenantOverride": tenant_overrides,
        "invalidErrorCode": invalid_errors,
        "eventContract": events["failure"],
        "frontendContract": frontend["failure"],
    }
    hard_failure_count = sum(len(value) for value in hard_failures.values())
    result = {
        "requirementId": "T2-API-001",
        "evidenceLevel": "STATIC_AND_SOFTWARE_EXECUTION",
        "status": "PASS" if hard_failure_count == 0 else "FAIL",
        "controllerOperationCount": len(controllers),
        "openApiOperationCount": len(contracts),
        "openApiFiles": contract_files,
        "permissionExceptionCount": len(permission_exceptions),
        "permissionExceptions": permission_exceptions,
        "errorCodeCount": len(errors),
        "errorCatalog": errors,
        "eventContracts": events,
        "frontendContracts": frontend,
        "hardFailureCount": hard_failure_count,
        "hardFailures": hard_failures,
        "invariants": {
            "trustedTenantOnly": not tenant_overrides,
            "providerNetworkCallsAllowed": 0,
            "realCallbackEndpointsAllowed": 0,
            "frontendDomainRecalculationAllowed": False,
        },
    }
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"T2 API audit {result['status']}: controllers={len(controllers)}, "
        f"openapi={len(contracts)}, errorCodes={len(errors)}, hardFailures={hard_failure_count}"
    )
    return 0 if hard_failure_count == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
