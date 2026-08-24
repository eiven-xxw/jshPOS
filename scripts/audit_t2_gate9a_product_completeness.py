#!/usr/bin/env python3
"""生成 Gate 9A 当前 87 项需求、22 Owner、页面/API 和缺陷完整性证据。"""
from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import pathlib
import re
import subprocess
from collections import Counter


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9a-prep"
RTM = ROOT / "docs/governance/rtm.csv"
MARKER = re.compile(r"\b(Fake|Mock|Stub|Locked|InMemory|TODO|FIXME|UnsupportedOperationException|UnimplementedError)\b", re.I)
SOURCE_SUFFIXES = {".java", ".xml", ".sql", ".ts", ".vue", ".dart"}


def load_json(name: str) -> dict:
    return json.loads((CONTRACT / name).read_text(encoding="utf-8"))


def relative(path: pathlib.Path) -> str:
    return path.relative_to(ROOT).as_posix()


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True, encoding="utf-8").strip()


def load_api_auditor():
    source = ROOT / "scripts/audit_t2_gate6g_api.py"
    spec = importlib.util.spec_from_file_location("gate6g_api_audit", source)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load historical API auditor")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def accepted_requirements() -> list[dict[str, str]]:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        return [
            row for row in csv.DictReader(handle)
            if row["phase"] == "T2" and row["status"] == "ACCEPTED"
        ]


def all_t2_states() -> dict[str, str]:
    with RTM.open(encoding="utf-8-sig", newline="") as handle:
        return {
            row["requirement_id"]: row["status"]
            for row in csv.DictReader(handle)
            if row["phase"] == "T2"
        }


def reference_exists(value: str) -> bool:
    value = value.strip()
    if not value:
        return False
    if value.startswith(("http://", "https://", "GitHub Actions Run", "GitHub Run")):
        return True
    if re.fullmatch(r"V\d+", value) or re.fullmatch(r"jshpos-[a-z0-9-]+", value):
        return True
    return (ROOT / value).exists()


def module_assets(modules: list[str]) -> list[dict]:
    admin_pom = read(ROOT / "server/ruoyi-admin/pom.xml")
    reactor_pom = read(ROOT / "server/ruoyi-modules/pom.xml")
    parent_pom = read(ROOT / "server/pom.xml")
    rows: list[dict] = []
    for module in modules:
        base = ROOT / f"server/ruoyi-modules/jshpos-{module}"
        main = base / "src/main"
        tests = base / "src/test"
        java = sorted(main.glob("java/**/*.java"))
        texts = {path: read(path) for path in java}
        controllers = [path for path, text in texts.items() if "@RestController" in text]
        app = [path for path in java if "/application/" in path.as_posix()]
        domain = [path for path in java if "/domain/" in path.as_posix()]
        persistence = [
            path for path in java
            if "/infrastructure/persistence/" in path.as_posix()
            or path.name.endswith(("Mapper.java", "Repository.java"))
        ] + list(main.glob("resources/mapper/**/*.xml"))
        migrations = list(main.glob("resources/db/migration/*.sql"))
        test_files = list(tests.glob("**/*Test.java")) + list(tests.glob("**/*IT.java"))
        event_files = [
            path for path, text in texts.items()
            if re.search(r"\b(Outbox|Inbox|DomainEvent|EventPublisher|EventRecord)\b", text)
        ]
        permission_ops = sum(text.count("@SaCheckPermission") for text in texts.values())
        audit_ops = sum(text.count("@Log(") for text in texts.values())
        rows.append({
            "module": module,
            "path": relative(base),
            "pom": (base / "pom.xml").is_file(),
            "javaFiles": len(java),
            "controllers": len(controllers),
            "applicationFiles": len(app),
            "domainFiles": len(domain),
            "persistenceFiles": len(persistence),
            "migrationFiles": len(migrations),
            "eventOrOutboxFiles": len(set(event_files)),
            "permissionAnnotations": permission_ops,
            "auditAnnotations": audit_ops,
            "tests": len(test_files),
            "adminAssembly": f"jshpos-{module}" in admin_pom,
            "reactorAssembly": f"jshpos-{module}" in reactor_pom,
            "dependencyManagement": f"jshpos-{module}" in parent_pom,
        })
    return rows


def owners_for_requirement(requirement_id: str, catalog: dict) -> list[str]:
    prefix = requirement_id.split("-")[1]
    cross = catalog.get("crossOwnerRequirements", {}).get(prefix)
    if cross:
        return list(cross)
    result = [item["module"] for item in catalog["owners"] if prefix in item.get("prefixes", [])]
    return result or ["integration"]


def surface_index(ui_catalog: dict) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    for surface in ui_catalog["vueSurfaces"] + ui_catalog["flutterSurfaces"]:
        for requirement in surface["requirements"]:
            result.setdefault(requirement, []).append(surface["path"])
    return result


def requirement_coverage(accepted: list[dict[str, str]], module_rows: list[dict], owner_catalog: dict,
                         ui_catalog: dict) -> tuple[list[dict], list[str]]:
    assets = {item["module"]: item for item in module_rows}
    surfaces = surface_index(ui_catalog)
    failures: list[str] = []
    rows: list[dict] = []
    for item in accepted:
        requirement_id = item["requirement_id"]
        owners = owners_for_requirement(requirement_id, owner_catalog)
        refs = [part.strip() for part in item["implementation"].split(";") if part.strip()]
        reachable = sum(1 for ref in refs if reference_exists(ref))
        if reachable == 0:
            failures.append(f"{requirement_id}: no reachable implementation reference")
        owner_assets = [assets[owner] for owner in owners if owner in assets]
        controller_count = sum(row["controllers"] for row in owner_assets)
        domain_count = sum(row["domainFiles"] for row in owner_assets)
        event_count = sum(row["eventOrOutboxFiles"] for row in owner_assets)
        permission_count = sum(row["permissionAnnotations"] for row in owner_assets)
        audit_count = sum(row["auditAnnotations"] for row in owner_assets)
        rows.append({
            "requirementId": requirement_id,
            "domain": item["domain"],
            "priority": item["priority"],
            "owners": "|".join(owners),
            "page": "|".join(surfaces.get(requirement_id, ["N/A_BY_REQUIREMENT_SCOPE"])),
            "apiControllers": controller_count,
            "apiCoverage": "FORMAL_CONTROLLER" if controller_count else "N/A_COMPOSITION_OR_QUALITY_SCOPE",
            "applicationFiles": sum(row["applicationFiles"] for row in owner_assets),
            "domainFiles": domain_count,
            "domainCoverage": "FORMAL_DOMAIN" if domain_count else "N/A_COMPOSITION_OR_QUALITY_SCOPE",
            "repositoryMapperFiles": sum(row["persistenceFiles"] for row in owner_assets),
            "migrationFiles": sum(row["migrationFiles"] for row in owner_assets),
            "eventOutboxFiles": event_count,
            "eventOutboxCoverage": "FORMAL_EVENT_OR_OUTBOX" if event_count else "N/A_BY_OWNER_COLLABORATION_SCOPE",
            "permissionAnnotations": permission_count,
            "auditAnnotations": audit_count,
            "permissionAuditCoverage": "FORMAL_PROTOCOL_AND_AUDIT" if permission_count or audit_count else "N/A_NON_HTTP_COMPOSITION_SCOPE",
            "testFiles": sum(row["tests"] for row in owner_assets),
            "implementationReferences": len(refs),
            "reachableReferences": reachable,
            "testEvidenceDefined": bool(item["test_evidence"].strip()),
            "ciEvidence": item["test_evidence"].strip() or "MISSING",
        })
    return rows, failures


def surface_quality(ui_catalog: dict) -> tuple[list[dict], list[dict]]:
    rows: list[dict] = []
    gaps: list[dict] = []
    spec_files = list((ROOT / "admin-web/src").glob("**/*.spec.ts"))
    spec_text = "\n".join(read(path) for path in spec_files)
    flutter_tests = list((ROOT / "pos-flutter/test").glob("**/*test.dart"))
    flutter_test_text = "\n".join(read(path) for path in flutter_tests)
    for kind, surfaces in (("VUE", ui_catalog["vueSurfaces"]), ("FLUTTER", ui_catalog["flutterSurfaces"])):
        for surface in surfaces:
            path = ROOT / surface["path"]
            text = read(path) if path.exists() else ""
            if kind == "VUE":
                actions = len(re.findall(r"<el-button\b", text))
                permissions = len(re.findall(r"v-hasPermi", text))
                busy = len(re.findall(r"\b(?:loading|disabled|submitting|pending)\b", text, re.I))
                errors = len(re.findall(r"ElMessage\.(?:error|warning)|catch\s*\(", text))
                empty = len(re.findall(r"el-empty|empty-text|暂无|无数据", text))
                direct_access = len(re.findall(r"\b(fetch\s*\(|axios\.|indexedDB|localStorage\.)", text))
                token = path.name if path.name != "index.vue" else path.parent.name
                direct_test = token in spec_text
                api_exists = (ROOT / f"admin-web/src/api/{surface['apiRoot']}").exists()
            else:
                actions = len(re.findall(r"\b(?:FilledButton|ElevatedButton|OutlinedButton|TextButton|IconButton|InkWell|GestureDetector)\b", text))
                permissions = len(re.findall(r"\b(?:permission|can[A-Z]|authorized|denied)\b", text, re.I))
                busy = len(re.findall(r"\b(?:busy|loading|isLoading|submitting|processing|disabled)\b", text, re.I))
                errors = len(re.findall(r"SnackBar|showDialog|catch\s*\(|\bError\b", text))
                empty = len(re.findall(r"empty|暂无|无数据|没有", text, re.I))
                direct_access = len(re.findall(r"MethodChannel|sqflite|rawQuery|rawInsert|rawUpdate|rawDelete", text))
                token = path.stem.replace("pos_", "").replace("_page", "").replace("_shell", "")
                direct_test = token in flutter_test_text
                api_exists = True
            row = {
                "kind": kind,
                "path": surface["path"],
                "requirements": "|".join(surface["requirements"]),
                "actions": actions,
                "permissionSignals": permissions,
                "busyOrSingleFlightSignals": busy,
                "explicitErrorSignals": errors,
                "explicitEmptySignals": empty,
                "directRuntimeAccessViolations": direct_access,
                "apiModuleExists": api_exists,
                "directUiTestSignal": direct_test,
            }
            rows.append(row)
            reasons = []
            if not path.exists():
                reasons.append("surface missing")
            if not api_exists:
                reasons.append("API module missing")
            if direct_access:
                reasons.append("UI directly accesses transport/database/device primitive")
            if actions and busy == 0:
                reasons.append("mutation surface has no local busy/single-flight signal")
            if actions and errors == 0:
                reasons.append("mutation surface has no explicit local error/recovery signal")
            if not direct_test:
                reasons.append("no direct UI test signal")
            if reasons:
                gaps.append({"path": surface["path"], "severity": "REVIEW", "reasons": reasons})
    return rows, gaps


def production_markers() -> tuple[list[dict], list[dict]]:
    roots = [
        *sorted((ROOT / "server/ruoyi-modules").glob("jshpos-*/src/main")),
        ROOT / "admin-web/src/api",
        ROOT / "admin-web/src/views/catalog",
        ROOT / "admin-web/src/views/foundation",
        ROOT / "admin-web/src/views/operations",
        ROOT / "admin-web/src/views/reporting",
        ROOT / "admin-web/src/views/saas",
        ROOT / "admin-web/src/views/service",
        ROOT / "admin-web/src/views/subscription",
        ROOT / "admin-web/src/views/terminal",
        ROOT / "pos-flutter/lib",
        ROOT / "packages/pos_device_adapter/lib",
    ]
    allowed: list[dict] = []
    unresolved: list[dict] = []
    for base in roots:
        if not base.exists():
            continue
        for path in sorted(base.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in SOURCE_SUFFIXES:
                continue
            if "__tests__" in path.parts or path.name.endswith(("Test.java", "IT.java", ".spec.ts", "_test.dart")):
                continue
            for line_number, line in enumerate(read(path).splitlines(), 1):
                match = MARKER.search(line)
                if not match:
                    continue
                rel = relative(path)
                reason = None
                if "locked_pos_" in path.name:
                    reason = "外部支付或硬件未解阻时的显式失败关闭适配器"
                elif path.name == "pos_local_database.dart" and "inMemory" in line:
                    reason = "仅测试与合成执行显式调用的内存数据库构造器"
                elif path.name == "pos_device_adapter_platform_interface.dart" and "UnimplementedError" in line:
                    reason = "Flutter 插件平台接口未提供实现时失败关闭"
                elif "FAKE_TEST" in line:
                    reason = "Provider 观察源枚举及生产拒绝规则；不表示生产成功实现"
                elif rel.endswith("RuoYiServiceAttachmentStorageAdapter.java") and "UnsupportedOperationException" in line:
                    reason = "Windows 不支持 POSIX 权限时保留受控目录 ACL 的兼容分支"
                elif rel.endswith("LotInventoryService.java") and match.group(1).lower() == "locked":
                    reason = "持有锁并完成重建校验后的批次快照局部变量，不是 Locked 临时适配器"
                elif rel.endswith("promotion_engine.dart") and match.group(1).lower() == "locked":
                    reason = "促销行互斥选择的确定性计算状态，不是 Locked 临时实现"
                elif "features/session/" in rel and match.group(1).lower() == "locked":
                    reason = "POS 会话失败关闭状态，未认证或能力受限时禁止业务操作"
                elif rel.endswith("pos_application_bootstrap.dart") and match.group(1).lower() == "locked":
                    reason = "正式配置缺失时应用组合根失败关闭，不伪造会话或业务成功"
                record = {
                    "path": rel,
                    "line": line_number,
                    "marker": match.group(1),
                    "text": line.strip()[:240],
                }
                if reason:
                    record["classification"] = "REVIEWED_FAIL_CLOSED_OR_TEST_BOUNDARY"
                    record["reason"] = reason
                    allowed.append(record)
                else:
                    record["classification"] = "UNCLASSIFIED_PRODUCTION_MARKER"
                    unresolved.append(record)
    return allowed, unresolved


def api_drift() -> dict:
    auditor = load_api_auditor()
    controllers, permission_exceptions = auditor.controller_operations()
    contracts, duplicate_ids, contract_files = auditor.openapi_operations()
    historical, historical_failures = auditor.historical_openapi_files()
    return {
        "controllerCount": len(controllers),
        "openApiOperationCount": len(contracts),
        "controllerOperations": [controllers[key] for key in sorted(controllers)],
        "controllerWithoutOpenApi": [controllers[key] for key in sorted(controllers.keys() - contracts.keys())],
        "openApiWithoutController": [contracts[key] for key in sorted(contracts.keys() - controllers.keys())],
        "duplicateOperationIds": duplicate_ids,
        "permissionExceptions": permission_exceptions,
        "contractFiles": contract_files,
        "historicalContracts": historical,
        "historicalFailures": historical_failures,
    }


def client_api_roots(ui_catalog: dict, api: dict) -> list[dict]:
    """验证页面引用的业务 API 根至少存在正式服务端 Controller 操作。"""
    roots = sorted({surface["apiRoot"] for surface in ui_catalog["vueSurfaces"]})
    controller_paths = [item["path"] for item in api["controllerOperations"]]
    rows: list[dict] = []
    for root in roots:
        source_root = ROOT / f"admin-web/src/api/{root}"
        sources = sorted(source_root.glob("*.ts"))
        literals: set[str] = set()
        for source in sources:
            literals.update(re.findall(r"['\"](/api/[^'\"]+)['\"]", read(source)))
        matches = {
            literal: sorted(path for path in controller_paths if path == literal or path.startswith(literal.rstrip("/") + "/"))
            for literal in sorted(literals)
        }
        rows.append({
            "apiRoot": root,
            "sourceFiles": len(sources),
            "declaredEndpointRoots": len(literals),
            "matchedEndpointRoots": sum(1 for value in matches.values() if value),
            "unmatchedEndpointRoots": [key for key, value in matches.items() if not value],
            "formalServerBacked": bool(sources) and bool(literals) and all(matches.values()),
        })
    return rows


def write_csv(path: pathlib.Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not rows:
        path.write_text("\n", encoding="utf-8")
        return
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()
    output = args.output_dir if args.output_dir.is_absolute() else ROOT / args.output_dir
    output.mkdir(parents=True, exist_ok=True)

    admission = load_json("gate9a-admission.json")
    owner_catalog = load_json("owner-catalog-v1.json")
    ui_catalog = load_json("ui-surface-catalog-v1.json")
    accepted = accepted_requirements()
    states = all_t2_states()
    modules = [item["module"] for item in owner_catalog["owners"]]
    module_rows = module_assets(modules)
    coverage, coverage_failures = requirement_coverage(accepted, module_rows, owner_catalog, ui_catalog)
    surfaces, surface_gaps = surface_quality(ui_catalog)
    allowed_markers, unresolved_markers = production_markers()
    api = api_drift()
    client_roots = client_api_roots(ui_catalog, api)

    hard_failures: list[str] = []
    if len(accepted) != admission["expectedAcceptedT2Requirements"]:
        hard_failures.append(f"accepted requirement count drift: {len(accepted)}")
    if len(modules) != admission["expectedOwnerModules"] or len(set(modules)) != len(modules):
        hard_failures.append(f"owner module catalog drift: {len(modules)}")
    incomplete_modules = [
        row["module"] for row in module_rows
        if not row["pom"] or not row["javaFiles"] or not row["tests"]
        or not row["adminAssembly"] or not row["reactorAssembly"] or not row["dependencyManagement"]
    ]
    if incomplete_modules:
        hard_failures.append(f"owner assembly or source/test incomplete: {incomplete_modules}")
    hard_failures.extend(coverage_failures)
    for requirement, expected in admission["requiredExternalStates"].items():
        if states.get(requirement) != expected:
            hard_failures.append(f"{requirement} state drift: {states.get(requirement)} != {expected}")
    if unresolved_markers:
        hard_failures.append(f"unclassified production temporary markers: {len(unresolved_markers)}")
    unbacked_client_roots = [item for item in client_roots if not item["formalServerBacked"]]

    demo_dependency = "<artifactId>ruoyi-demo</artifactId>" in read(ROOT / "server/ruoyi-admin/pom.xml")
    demo_views = sorted(relative(path) for path in (ROOT / "admin-web/src/views/demo").glob("**/*.vue"))
    demo_menu_seed = "demo:demo:list" in read(ROOT / "server/script/sql/ry_vue_5.X.sql")
    gate7e = load_json_from(ROOT / "contracts/t2/gate7e/e2e004-admission.json")
    gate8b = load_json_from(ROOT / "contracts/t2/gate8b/runtime-journey-v1.json")

    findings: list[dict] = []
    findings.append({
        "findingId": "G9A-AUD-P1-001",
        "severity": "P1",
        "category": "AUDIT_COVERAGE",
        "state": "CLOSED_IN_GATE9A_TOOLING",
        "title": "历史完整性审计只覆盖 15 个 Owner 且存在兼容分支误报",
        "evidence": ["scripts/audit_t2_gate6g_core.py", "scripts/audit_t2_gate9a_product_completeness.py"],
        "closure": "保留历史工具不变；当前审计固定覆盖 22 Owner 并逐条分类失败关闭与测试边界。",
    })
    if api["controllerWithoutOpenApi"] or api["openApiWithoutController"]:
        findings.append({
            "findingId": "G9A-API-P1-001",
            "severity": "P1",
            "category": "FORMAL_API_CONTRACT",
            "state": "OPEN",
            "title": "当前 Controller 与正式 OpenAPI 存在双向漂移",
            "metrics": {
                "controllerWithoutOpenApi": len(api["controllerWithoutOpenApi"]),
                "openApiWithoutController": len(api["openApiWithoutController"]),
                "clientApiRootsWithoutFormalServer": len(unbacked_client_roots),
            },
            "evidence": ["artifacts/t2/gate9a-prep/product-audit/api-drift.json", "artifacts/t2/gate9a-prep/product-audit/client-api-roots.json"],
            "closure": "逐操作确定权威路径、参数、operationId 和替代契约；客户端契约测试与 Controller/OpenAPI 300 项双向一致。",
        })
    if demo_dependency or demo_views or demo_menu_seed:
        findings.append({
            "findingId": "G9A-ASM-P1-001",
            "severity": "P1",
            "category": "PRODUCTION_ASSEMBLY",
            "state": "OPEN",
            "title": "RuoYi 演示模块、演示页面和初始化菜单仍进入正式装配或发行源",
            "metrics": {"serverDemoDependency": demo_dependency, "demoViews": len(demo_views), "demoMenuSeed": demo_menu_seed},
            "evidence": ["server/ruoyi-admin/pom.xml", "server/ruoyi-modules/ruoyi-demo", "admin-web/src/views/demo", "server/script/sql/ry_vue_5.X.sql"],
            "closure": "从商业构建与初始化数据中移除演示模块/页面/菜单，保留需要的框架能力并补启动、路由、依赖和回归测试。",
        })
    ui_review = [gap for gap in surface_gaps if gap["reasons"]]
    if ui_review:
        findings.append({
            "findingId": "G9A-UI-P1-001",
            "severity": "P1",
            "category": "UI_ACCEPTANCE_EVIDENCE",
            "state": "OPEN",
            "title": "部分正式页面缺少可定位的页面级错误恢复、单飞或直接组件测试证据",
            "metrics": {"businessSurfaces": len(surfaces), "reviewRequired": len(ui_review)},
            "evidence": ["artifacts/t2/gate9a-prep/product-audit/page-api-matrix.csv"],
            "closure": "逐页面确认按钮权限、空/错/加载状态、重复点击和恢复行为；补直接组件/Widget 测试，无法证明者按缺陷修复。",
        })
    gate7e_saa_state = gate7e.get("preservedStates", {}).get("T2-SAA-001")
    saas_was_draft = gate7e_saa_state == "DRAFT"
    single_industry_saas = set(gate8b.get("industries", [])) != {"CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET"}
    if saas_was_draft or single_industry_saas:
        findings.append({
            "findingId": "G9A-E2E-P1-001",
            "severity": "P1",
            "category": "CURRENT_FULL_STACK_E2E",
            "state": "OPEN",
            "title": "三业态业务闭环与 SAA/SUB/SVC 正式 API 旅程尚未在同一 22 Owner 当前栈联合验证",
            "metrics": {"gate7eSaaState": gate7e_saa_state, "gate8bJourney": gate8b.get("journey")},
            "evidence": ["contracts/t2/gate7e/e2e004-admission.json", "contracts/t2/gate8b/runtime-journey-v1.json"],
            "closure": "在正式 MySQL/Redis/JAR/HTTP、Flutter/SQLite 和三业态虚构数据下，经正式 API 联合复跑 22 Owner，不使用数据库后门。",
        })

    register = load_json("defect-register-v1.json")
    registered_ids = {item["findingId"] for item in register["findings"]}
    observed_ids = {item["findingId"] for item in findings}
    if registered_ids != observed_ids:
        hard_failures.append(
            f"defect register drift: registered={sorted(registered_ids)} observed={sorted(observed_ids)}"
        )
    registered_open_p0 = sum(1 for item in register["findings"] if item["severity"] == "P0" and item["state"] == "OPEN")
    registered_open_p1 = sum(1 for item in register["findings"] if item["severity"] == "P1" and item["state"] == "OPEN")

    open_p0 = sum(1 for item in findings if item["severity"] == "P0" and item["state"] == "OPEN")
    open_p1 = sum(1 for item in findings if item["severity"] == "P1" and item["state"] == "OPEN")
    if (registered_open_p0, registered_open_p1) != (open_p0, open_p1):
        hard_failures.append(
            f"defect severity summary drift: register={registered_open_p0}/{registered_open_p1} observed={open_p0}/{open_p1}"
        )
    result = {
        "schemaVersion": "1.0",
        "requirementId": "T2-CMP-001",
        "commitSha": git("rev-parse", "HEAD"),
        "evidenceLevel": admission["evidenceLevel"],
        "acceptedRequirementCount": len(accepted),
        "ownerModuleCount": len(modules),
        "moduleTotals": {
            "controllers": sum(row["controllers"] for row in module_rows),
            "applicationFiles": sum(row["applicationFiles"] for row in module_rows),
            "domainFiles": sum(row["domainFiles"] for row in module_rows),
            "persistenceFiles": sum(row["persistenceFiles"] for row in module_rows),
            "migrationFiles": sum(row["migrationFiles"] for row in module_rows),
            "tests": sum(row["tests"] for row in module_rows),
        },
        "uiTotals": {"surfaces": len(surfaces), "reviewRequired": len(surface_gaps)},
        "apiTotals": {
            "controllers": api["controllerCount"],
            "openApiOperations": api["openApiOperationCount"],
            "controllerWithoutOpenApi": len(api["controllerWithoutOpenApi"]),
            "openApiWithoutController": len(api["openApiWithoutController"]),
            "clientApiRoots": len(client_roots),
            "clientApiRootsWithoutFormalServer": len(unbacked_client_roots),
        },
        "productionMarkers": {"reviewed": len(allowed_markers), "unclassified": len(unresolved_markers)},
        "findings": {"openP0": open_p0, "openP1": open_p1, "total": len(findings)},
        "hardFailures": hard_failures,
        "result": "PASS" if not hard_failures else "FAIL",
        "recommendation": "CONDITIONAL_PASS_AUDIT_ONLY" if not hard_failures else "NO_GO_AUDIT_INCOMPLETE",
    }

    write_csv(output / "requirement-coverage.csv", coverage)
    write_csv(output / "owner-module-matrix.csv", module_rows)
    write_csv(output / "page-api-matrix.csv", surfaces)
    (output / "surface-review-gaps.json").write_text(json.dumps(surface_gaps, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "production-markers.json").write_text(json.dumps({"reviewed": allowed_markers, "unclassified": unresolved_markers}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "api-drift.json").write_text(json.dumps(api, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "client-api-roots.json").write_text(json.dumps(client_roots, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "defect-ledger.json").write_text(json.dumps({"schemaVersion": "1.0", "findings": findings}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"T2 Gate9A audit {result['result']}: accepted={len(accepted)} owners={len(modules)} P0={open_p0} P1={open_p1} hard={len(hard_failures)}")
    if hard_failures:
        raise SystemExit("\n".join(f"- {failure}" for failure in hard_failures))
    return 0


def load_json_from(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    raise SystemExit(main())
