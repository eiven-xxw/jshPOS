#!/usr/bin/env python3
"""校验 Gate 9B 的 300 项 Controller/OpenAPI 双向闭环及边界守恒。"""
from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import pathlib
import re
import subprocess


ROOT = pathlib.Path(__file__).resolve().parents[1]
BASELINE = "f708271e977f995e83a24fe398a1bd658726fd09"
CONTRACT = ROOT / "contracts/t2/gate9b"
ALIGNED_CONTRACTS = {
    "contracts/t2/gate7c-prd005/openapi-weighted-barcode-v1.yaml",
    "contracts/t2/gate7c-rpl001/openapi-replenishment-v1.yaml",
    "contracts/t2/gate7d-exc001/openapi-exception-center-v1.yaml",
    "contracts/t2/gate7d-mem003/openapi-member-benefit-price-v1.yaml",
    "contracts/t2/gate8a-saa001/openapi-saas-v1.yaml",
    "contracts/t2/gate8a-sub001/openapi-subscription-v1.yaml",
    "contracts/t2/gate8a-svc001/openapi-service-v1.yaml",
}
RUNTIME_PREFIXES = ("server/", "admin-web/", "pos-flutter/", "packages/", "infra/")
HTTP_METHODS = {"get", "post", "put", "delete", "patch"}


def fail(message: str) -> None:
    raise AssertionError(message)


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def load_api_auditor():
    source = ROOT / "scripts/audit_t2_gate6g_api.py"
    spec = importlib.util.spec_from_file_location("gate6g_api_audit", source)
    if spec is None or spec.loader is None:
        fail("无法加载历史 API 审计器")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def rtm_states() -> dict[str, str]:
    with (ROOT / "docs/governance/rtm.csv").open(encoding="utf-8-sig", newline="") as handle:
        return {row["requirement_id"]: row["status"] for row in csv.DictReader(handle)}


def parse_contract_permissions(path: pathlib.Path, auditor) -> dict[tuple[str, str], str | None]:
    """读取本批当前 OpenAPI 的逐操作权限，不依赖额外 YAML 运行库。"""
    text = path.read_text(encoding="utf-8")
    server_match = re.search(r"^servers:\s*\n\s*-\s*url:\s*([^\s#]+)", text, re.MULTILINE)
    server = server_match.group(1).strip("'\"") if server_match else ""
    lines = text.splitlines()
    current_path: str | None = None
    result: dict[tuple[str, str], str | None] = {}
    for index, line in enumerate(lines):
        path_match = re.match(r"^  (/.*):\s*$", line)
        if path_match:
            current_path = path_match.group(1)
            continue
        method_match = re.match(r"^    (get|post|put|delete|patch):\s*$", line)
        if not method_match or current_path is None:
            continue
        end = index + 1
        while end < len(lines) and not re.match(r"^(  /.*|    (?:get|post|put|delete|patch):)\s*$", lines[end]):
            end += 1
        block = "\n".join(lines[index:end])
        permission_match = re.search(r"^\s+x-permission:\s*([^\s#]+)", block, re.MULTILINE)
        key = (method_match.group(1).upper(), auditor.join_path(server, current_path))
        result[key] = permission_match.group(1) if permission_match else None
    return result


def client_root_evidence(controller_operations: dict[tuple[str, str], dict]) -> list[dict]:
    catalog = json.loads((ROOT / "contracts/t2/gate9a-prep/ui-surface-catalog-v1.json").read_text(encoding="utf-8"))
    roots = sorted({item["apiRoot"] for item in catalog["vueSurfaces"]})
    controller_paths = [key[1] for key in controller_operations]
    rows: list[dict] = []
    for root in roots:
        directory = ROOT / f"admin-web/src/api/{root}"
        sources = sorted(directory.glob("*.ts"))
        text = "\n".join(path.read_text(encoding="utf-8") for path in sources)
        literals = sorted(set(re.findall(r"['\"](/api/(?:v1|pos/v1)/[^'\"]*)['\"]", text)))
        unmatched = [
            literal for literal in literals
            if not any(path == literal or path.startswith(literal.rstrip("/") + "/")
                       or literal.startswith(path.rstrip("/") + "/") for path in controller_paths)
        ]
        contract_tests = sorted(directory.glob("__tests__/*.spec.ts"))
        rows.append({
            "apiRoot": root,
            "sourceFiles": len(sources),
            "requestSignals": len(re.findall(r"\brequest\s*\(|\b(?:get|post|put|del)<", text)),
            "declaredEndpointRoots": len(literals),
            "unmatchedEndpointRoots": unmatched,
            "contractTests": len(contract_tests),
            "formalServerBacked": bool(sources) and bool(literals) and not unmatched and bool(contract_tests),
        })
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    args = parser.parse_args()

    admission = json.loads((CONTRACT / "gate9b-admission.json").read_text(encoding="utf-8"))
    classification = json.loads((CONTRACT / "api-difference-classification-v1.json").read_text(encoding="utf-8"))
    closure = json.loads((CONTRACT / "defect-closure-v1.json").read_text(encoding="utf-8"))
    if subprocess.run(["git", "merge-base", "--is-ancestor", BASELINE, "HEAD"], cwd=ROOT).returncode != 0:
        fail("Gate 9A 最终封存提交不是当前分支祖先")

    states = rtm_states()
    expected_states = {"T2-CMP-001": "ACCEPTED", "T2-API-001": "ACCEPTED", **admission["preservedStates"]}
    drift = {key: states.get(key) for key, expected in expected_states.items() if states.get(key) != expected}
    if drift:
        fail(f"RTM 状态漂移: {drift}")

    # 本轮结论是契约修复；运行时与已发布迁移必须保持基线不变。
    changed = git("diff", "--name-only", BASELINE).splitlines()
    runtime_changes = [path for path in changed if path.startswith(RUNTIME_PREFIXES)]
    migration_changes = [path for path in changed if "/db/migration/" in path or "sqlite_migrations" in path]
    if runtime_changes or migration_changes:
        fail(f"本批出现未准入运行时或迁移变更: runtime={runtime_changes}, migration={migration_changes}")
    immutable = ["scripts/audit_t2_gate6g_api.py", "contracts/t2/gate9a-prep/defect-register-v1.json"]
    changed_immutable = [path for path in immutable if subprocess.run(
        ["git", "diff", "--quiet", BASELINE, "--", path], cwd=ROOT).returncode != 0]
    if changed_immutable:
        fail(f"历史审计或原始缺陷账被修改: {changed_immutable}")

    groups = classification["groups"]
    if len(groups) != 10 or sum(item["controllerWithoutOpenApi"] for item in groups) != 64 \
            or sum(item["openApiWithoutController"] for item in groups) != 21:
        fail("85 项基线差异未被十个 Owner 分类完整覆盖")
    for item in groups:
        if not (ROOT / item["controllerSource"]).is_file() or not (ROOT / item["openApiSource"]).is_file():
            fail(f"分类来源不可定位: {item['owner']}")

    auditor = load_api_auditor()
    controllers, permission_exceptions = auditor.controller_operations()
    openapi, duplicate_ids, selected_files = auditor.openapi_operations()
    historical, historical_failures = auditor.historical_openapi_files()
    errors, invalid_errors = auditor.error_catalog()
    frontend = auditor.frontend_contract_summary()
    controller_without = sorted(controllers.keys() - openapi.keys())
    openapi_without = sorted(openapi.keys() - controllers.keys())
    missing_operation_id = [item for item in openapi.values() if not item["operationId"]]
    missing_permissions = [item for item in controllers.values() if item.get("permissionFailure")]
    tenant_overrides = auditor.tenant_override_failures()
    if len(controllers) != admission["exit"]["controllerOperations"] or len(openapi) != admission["exit"]["currentOpenApiOperations"]:
        fail(f"操作总数不满足退出条件: controller={len(controllers)} openapi={len(openapi)}")
    if controller_without or openapi_without or duplicate_ids or missing_operation_id:
        fail(f"双向契约未闭环: controllerOnly={controller_without}, openApiOnly={openapi_without}, duplicate={duplicate_ids}")
    if missing_permissions or tenant_overrides or invalid_errors or historical_failures or frontend["failure"]:
        fail("权限、可信租户、错误码、历史替代或前端契约门禁失败")

    selected = set(selected_files)
    if not ALIGNED_CONTRACTS.issubset(selected):
        fail(f"Gate 9B 当前契约未被发现: {sorted(ALIGNED_CONTRACTS - selected)}")
    aligned_permissions: dict[tuple[str, str], str | None] = {}
    for relative in sorted(ALIGNED_CONTRACTS):
        path = ROOT / relative
        if "x-contract-authority: CURRENT_RUNTIME" not in path.read_text(encoding="utf-8"):
            fail(f"当前契约缺少权威标记: {relative}")
        aligned_permissions.update(parse_contract_permissions(path, auditor))
    permission_drift = [
        {"method": key[0], "path": key[1], "controller": controllers.get(key, {}).get("permission"), "openapi": permission}
        for key, permission in sorted(aligned_permissions.items())
        if key not in controllers or permission != controllers[key]["permission"]
    ]
    if permission_drift:
        fail(f"本批契约权限漂移: {permission_drift}")

    pointers = {
        "contracts/t2/gate8a-sub001/openapi.yaml": "contracts/t2/gate8a-sub001/openapi-subscription-v1.yaml",
        "contracts/t2/gate8a-svc001/openapi.yaml": "contracts/t2/gate8a-svc001/openapi-service-v1.yaml",
    }
    for source, target in pointers.items():
        text = (ROOT / source).read_text(encoding="utf-8")
        if "HISTORICAL_DRAFT_NON_RUNTIME" not in text or target not in text or not (ROOT / target).is_file():
            fail(f"历史契约指针无有效替代: {source}")

    clients = client_root_evidence(controllers)
    client_failures = [item for item in clients if not item["formalServerBacked"]]
    if len(clients) != admission["baseline"]["clientApiRoots"] or client_failures:
        fail(f"客户端 API 根未被正式服务端支持: {client_failures}")
    expected_closure = {
        "controllerOperations": len(controllers),
        "currentOpenApiOperations": len(openapi),
        "controllerWithoutOpenApi": len(controller_without),
        "openApiWithoutController": len(openapi_without),
        "missingOperationId": len(missing_operation_id),
        "duplicateOperationId": len(duplicate_ids),
        "alignedPermissions": len(aligned_permissions),
        "clientApiRootsWithoutFormalServer": len(client_failures),
    }
    if closure.get("state") not in {"VERIFIED_CLOSURE_CANDIDATE", "VERIFIED"} or closure.get("current") != expected_closure:
        fail(f"缺陷关闭候选与机器审计不一致: {closure.get('current')} != {expected_closure}")
    if closure.get("externalExecution") != admission["externalExecution"]:
        fail("缺陷关闭候选提升了外部执行证据")

    result = {
        "schemaVersion": "1.0",
        "gate": admission["gate"],
        "requirementId": "T2-API-001",
        "findingId": "G9A-API-P1-001",
        "commitSha": git("rev-parse", "HEAD"),
        "status": "PASS",
        "evidenceLevel": "STATIC_AND_SOFTWARE_EXECUTION",
        "baseline": admission["baseline"],
        "current": {
            "controllerOperations": len(controllers),
            "currentOpenApiOperations": len(openapi),
            "controllerWithoutOpenApi": len(controller_without),
            "openApiWithoutController": len(openapi_without),
            "duplicateOperationIds": len(duplicate_ids),
            "alignedPermissionOperations": len(aligned_permissions),
            "validErrorCodes": len(errors),
            "permissionExceptions": len(permission_exceptions),
            "clientApiRoots": len(clients),
            "clientApiRootsWithoutFormalServer": len(client_failures),
            "flutterHttpAdapters": frontend["posHttpAdapterCount"],
            "flutterContractTests": frontend["posContractTestCount"],
        },
        "classification": {"groups": len(groups), "controllerWithoutOpenApi": 64, "openApiWithoutController": 21},
        "clientEvidence": clients,
        "selectedCurrentOpenApiFiles": selected_files,
        "historicalOpenApiContracts": historical,
        "runtimeChanges": runtime_changes,
        "externalExecution": admission["externalExecution"],
        "closureCandidate": "CLOSED_IN_GATE9B",
    }
    if args.output:
        output = ROOT / args.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "T2 Gate9B API ALIGNMENT OK: "
        f"controllers={len(controllers)} openapi={len(openapi)} drift=0/0 "
        f"permissions={len(aligned_permissions)} clients={len(clients)} errors={len(errors)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
