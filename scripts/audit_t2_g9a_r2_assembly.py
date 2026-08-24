#!/usr/bin/env python3
"""复现 G9A-R2 当前商业装配中的演示与非 V1 平台能力信号。"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate9b-r2-prep"
MODULES = ("ruoyi-job", "ruoyi-generator", "ruoyi-demo", "ruoyi-workflow")
SQL_FILES = (
    "server/script/sql/ry_vue_5.X.sql",
    "server/script/sql/oracle/oracle_ry_vue_5.X.sql",
    "server/script/sql/postgres/postgres_ry_vue_5.X.sql",
    "server/script/sql/sqlserver/sqlserver_ry_vue_5.X.sql",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def direct_dependencies() -> list[str]:
    text = read("server/ruoyi-admin/pom.xml")
    return [module for module in MODULES if re.search(rf"<artifactId>{module}</artifactId>", text)]


def reactor_modules() -> list[str]:
    text = read("server/ruoyi-modules/pom.xml")
    return [module for module in MODULES if f"<module>{module}</module>" in text]


def accepted_owner_platform_refs() -> list[str]:
    failures: list[str] = []
    patterns = re.compile(r"org\.dromara\.(?:generator|workflow|job)|ruoyi-(?:generator|workflow|job)")
    modules = ROOT / "server/ruoyi-modules"
    for owner in sorted(modules.glob("jshpos-*")):
        for path in list(owner.rglob("*.java")) + [owner / "pom.xml"]:
            if path.is_file() and patterns.search(path.read_text(encoding="utf-8", errors="ignore")):
                failures.append(path.relative_to(ROOT).as_posix())
    return failures


def sql_evidence() -> list[dict]:
    rows: list[dict] = []
    for relative in SQL_FILES:
        lines = read(relative).splitlines()
        menu_rows = [
            line for line in lines
            if re.search(r"(?i)insert(?:\s+into)?\s+sys_menu", line)
            and ("demo:demo" in line or "demo:tree" in line or "测试菜单" in line)
        ]
        role_rows = [
            line for line in lines
            if re.search(r"(?i)insert(?:\s+into)?\s+sys_role_menu", line)
            and re.search(r"['\s,(](15(?:0[0-9]|1[01]))['\s,);]", line)
        ]
        table_rows = [
            line for line in lines
            if re.search(r"(?i)^\s*create\s+table(?:\s+if\s+not\s+exists)?\s+[`\[]?test_(?:demo|tree)", line)
        ]
        rows.append({
            "file": relative,
            "demoMenuRows": len(menu_rows),
            "demoRoleBindings": len(role_rows),
            "demoTables": len(table_rows),
        })
    return rows


def jar_evidence(path: pathlib.Path) -> dict:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
    libraries = [f"BOOT-INF/lib/{module}-5.6.2.jar" for module in MODULES]
    return {"path": str(path), "presentLibraries": [item for item in libraries if item in names]}


def sbom_evidence(path: pathlib.Path) -> dict:
    bom = json.loads(path.read_text(encoding="utf-8"))
    names = {item.get("name") for item in bom.get("components", [])}
    return {
        "path": str(path),
        "components": len(bom.get("components", [])),
        "presentModules": [module for module in MODULES if module in names],
    }


def web_dist_evidence(path: pathlib.Path) -> dict:
    signals = (b"/demo/demo", b"/demo/tree", b"demo:demo", b"demo:tree")
    matches: list[str] = []
    for item in sorted(path.rglob("*.js")):
        payload = item.read_bytes()
        if any(signal in payload for signal in signals):
            matches.append(item.relative_to(path).as_posix())
    return {"path": str(path), "compiledDemoSignalFiles": matches}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    parser.add_argument("--server-jar")
    parser.add_argument("--server-sbom")
    parser.add_argument("--web-dist")
    args = parser.parse_args()

    expected = json.loads((CONTRACT / "assembly-baseline-v1.json").read_text(encoding="utf-8"))
    demo_root = ROOT / "server/ruoyi-modules/ruoyi-demo"
    web_demo_views = sorted((ROOT / "admin-web/src/views/demo").rglob("*.vue"))
    web_demo_apis = sorted((ROOT / "admin-web/src/api/demo").rglob("*.ts"))
    app_config = read("server/ruoyi-admin/src/main/resources/application.yml")
    permission_store = read("admin-web/src/store/modules/permission.ts")
    owner_refs = accepted_owner_platform_refs()
    demo_main_java = demo_root / "src/main/java"
    static = {
        "directDependencies": direct_dependencies(),
        "defaultReactorModules": reactor_modules(),
        "demoControllers": len(list(demo_main_java.rglob("*Controller.java"))),
        "demoJavaFiles": len(list(demo_main_java.rglob("*.java"))),
        "demoResourceFiles": len([item for item in (demo_root / "src/main/resources").rglob("*") if item.is_file()]),
        "demoSpringdocGroup": "packages-to-scan: org.dromara.demo" in app_config,
        "warmFlowEnabledByDefault": bool(re.search(r"warm-flow:\s*\r?\n(?:.*\r?\n){0,3}\s+enabled:\s+true", app_config)),
        "warmFlowUiEnabledByDefault": bool(re.search(r"warm-flow:\s*\r?\n(?:.*\r?\n){0,5}\s+ui:\s+true", app_config)),
        "acceptedOwnerPlatformRefs": owner_refs,
        "demoViews": len(web_demo_views),
        "demoApiFiles": len(web_demo_apis),
        "dynamicViewGlobIncludesDemo": "import.meta.glob('./../../views/**/*.vue')" in permission_store,
        "generatorViews": len(list((ROOT / "admin-web/src/views/tool").rglob("*.vue"))),
        "workflowViews": len(list((ROOT / "admin-web/src/views/workflow").rglob("*.vue"))),
        "sql": sql_evidence(),
    }

    required = expected["server"]
    checks = {
        "direct dependencies": static["directDependencies"] == list(MODULES),
        "reactor modules": static["defaultReactorModules"] == list(MODULES),
        "demo controllers": static["demoControllers"] == required["demoControllers"],
        "demo java": static["demoJavaFiles"] == required["demoJavaFiles"],
        "demo resources": static["demoResourceFiles"] == required["demoResourceFiles"],
        "owner refs": not owner_refs,
        "web views": static["demoViews"] == expected["web"]["demoViews"],
        "web api": static["demoApiFiles"] == expected["web"]["demoApiFiles"],
        "view glob": static["dynamicViewGlobIncludesDemo"],
        "sql": all(
            item["demoMenuRows"] == 13 and item["demoRoleBindings"] == 24 and item["demoTables"] == 2
            for item in static["sql"]
        ),
    }
    failures = [name for name, ok in checks.items() if not ok]
    if failures:
        fail(f"装配基线与冻结事实不一致: {failures}")

    artifact_args = [args.server_jar, args.server_sbom, args.web_dist]
    if any(artifact_args) and not all(artifact_args):
        fail("制品模式必须同时提供 server JAR、SBOM 和 Web dist")
    artifacts: dict = {}
    if all(artifact_args):
        jar = jar_evidence(pathlib.Path(args.server_jar))
        sbom = sbom_evidence(pathlib.Path(args.server_sbom))
        web = web_dist_evidence(pathlib.Path(args.web_dist))
        if len(jar["presentLibraries"]) != 4 or len(sbom["presentModules"]) != 4:
            fail("当前 JAR 或 SBOM 未复现四个非 V1 模块")
        if len(web["compiledDemoSignalFiles"]) < 2:
            fail("当前 Web dist 未复现演示页面信号")
        artifacts = {"jar": jar, "sbom": sbom, "web": web}

    result = {
        "schemaVersion": "1.0",
        "gate": "T2-GATE9B-G9A-R2-PREP",
        "findingId": "G9A-ASM-P1-001",
        "status": "PASS_BASELINE_CONFIRMED",
        "findingState": "OPEN",
        "evidenceLevel": "STATIC_AND_LOCAL_BUILD_BASELINE" if artifacts else "STATIC_REPOSITORY_AUDIT",
        "static": static,
        "artifacts": artifacts,
        "externalExecution": {
            "providerNetwork": 0,
            "realFunds": 0,
            "realDeviceCommands": 0,
            "realPeripheralCommands": 0,
            "partnerFieldExecution": 0,
            "fullAlpha": 0,
            "productionDeployment": 0,
        },
    }
    if args.output:
        output = pathlib.Path(args.output)
        if not output.is_absolute():
            output = ROOT / output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        "G9A-R2 ASSEMBLY BASELINE CONFIRMED: demoControllers=19 demoViews=2 "
        "sqlDialects=4 jar/sbom/web=" + ("REBUILT" if artifacts else "STATIC")
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
