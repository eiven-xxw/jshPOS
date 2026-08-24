#!/usr/bin/env python3
"""验证 G9A-R2 商业默认装配、Web、SQL、配置与制品已同步收敛。"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
EXCLUDED = ("ruoyi-demo", "ruoyi-generator", "ruoyi-workflow", "ruoyi-job")
NON_COMMERCIAL_COMPONENTS = EXCLUDED + ("ruoyi-common-job",)
SQL_FILES = (
    "server/script/sql/ry_vue_5.X.sql",
    "server/script/sql/oracle/oracle_ry_vue_5.X.sql",
    "server/script/sql/postgres/postgres_ry_vue_5.X.sql",
    "server/script/sql/sqlserver/sqlserver_ry_vue_5.X.sql",
)
WEB_PATHS = (
    "admin-web/src/views/demo", "admin-web/src/api/demo",
    "admin-web/src/views/tool/gen", "admin-web/src/api/tool/gen",
    "admin-web/src/views/workflow", "admin-web/src/api/workflow",
    "admin-web/src/views/monitor/snailjob", "admin-web/src/api/monitor/snailjob",
)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def fail(message: str) -> None:
    raise AssertionError(message)


def artifact_names(path: pathlib.Path) -> set[str]:
    with zipfile.ZipFile(path) as archive:
        return set(archive.namelist())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output")
    parser.add_argument("--server-jar")
    parser.add_argument("--server-sbom")
    parser.add_argument("--web-dist")
    args = parser.parse_args()

    admin_pom = read("server/ruoyi-admin/pom.xml")
    root_pom = read("server/pom.xml")
    modules_pom = read("server/ruoyi-modules/pom.xml")
    common_pom = read("server/ruoyi-common/pom.xml")
    default_modules = modules_pom.split("<profiles>", 1)[0]
    default_common = common_pom.split("<profiles>", 1)[0]
    failures: list[str] = []
    if any(f"<artifactId>{name}</artifactId>" in admin_pom for name in EXCLUDED):
        failures.append("ruoyi-admin仍直接依赖非V1模块")
    if any(f"<artifactId>{name}</artifactId>" in root_pom for name in EXCLUDED):
        failures.append("根dependencyManagement仍发布非V1内部模块")
    if any(f"<module>{name}</module>" in default_modules for name in EXCLUDED):
        failures.append("默认ruoyi-modules reactor仍含非V1模块")
    if "<module>ruoyi-common-job</module>" in default_common:
        failures.append("默认ruoyi-common reactor仍含SnailJob适配层")
    if "<module>ruoyi-common-job</module>" not in common_pom:
        failures.append("SnailJob适配层未按显式非商业profile保留")
    for retained in ("ruoyi-generator", "ruoyi-workflow", "ruoyi-job"):
        if not (ROOT / f"server/ruoyi-modules/{retained}").is_dir() or f"<module>{retained}</module>" not in modules_pom:
            failures.append(f"{retained}未按显式非商业profile保留")
    demo_main = ROOT / "server/ruoyi-modules/ruoyi-demo/src/main"
    if (ROOT / "server/ruoyi-modules/ruoyi-demo/pom.xml").exists() or \
            (demo_main.exists() and any(path.is_file() for path in demo_main.rglob("*"))):
        failures.append("ruoyi-demo活动生产源码尚未删除")

    config = "\n".join(read(path) for path in (
        "server/ruoyi-admin/src/main/resources/application.yml",
        "server/ruoyi-admin/src/main/resources/application-dev.yml",
        "server/ruoyi-admin/src/main/resources/application-prod.yml",
    ))
    forbidden_config = ("org.dromara.demo", "org.dromara.generator", "org.dromara.workflow", "/warm-flow-ui/", "/warm-flow/save-json")
    if any(signal in config for signal in forbidden_config):
        failures.append("Springdoc或安全白名单仍暴露非V1平台入口")
    if "snail-job:" in config:
        failures.append("商业配置仍声明SnailJob入口")
    if not re.search(r"warm-flow:\s*\r?\n(?:.*\r?\n){0,4}\s+enabled:\s+false", config) or \
            not re.search(r"warm-flow:\s*\r?\n(?:.*\r?\n){0,6}\s+ui:\s+false", config):
        failures.append("WarmFlow未默认关闭")

    existing_web = [
        path for path in WEB_PATHS
        if (ROOT / path).exists() and any(item.is_file() for item in (ROOT / path).rglob("*"))
    ]
    if existing_web:
        failures.append(f"Web非V1生产面仍存在: {existing_web}")

    sql_failures: list[str] = []
    forbidden_sql = re.compile(
        r"(?i)test_demo|test_tree|demo:demo|demo:tree|demo/demo/index|demo/tree/index|"
        r"tool/gen/(?:index|editTable)|tool:gen:|monitor/snailjob/index|monitor:snailjob:|workflow/"
    )
    for relative in SQL_FILES:
        if forbidden_sql.search(read(relative)):
            sql_failures.append(relative)
    if sql_failures:
        failures.append(f"四方言初始化仍含演示或非V1菜单/表: {sql_failures}")

    owner_count = len([path for path in (ROOT / "server/ruoyi-modules").glob("jshpos-*") if path.is_dir()])
    if owner_count != 22:
        failures.append(f"Owner模块数量漂移: {owner_count}")
    artifacts: dict[str, object] = {}
    supplied = (args.server_jar, args.server_sbom, args.web_dist)
    if any(supplied) and not all(supplied):
        fail("制品模式必须同时提供 JAR、SBOM 和 Web dist")
    if all(supplied):
        jar_path = pathlib.Path(args.server_jar)
        jar_names = artifact_names(jar_path)
        jar_hits = sorted(name for name in jar_names if any(f"/{module}-" in name for module in NON_COMMERCIAL_COMPONENTS))
        sbom = json.loads(pathlib.Path(args.server_sbom).read_text(encoding="utf-8"))
        sbom_hits = sorted({component.get("name") for component in sbom.get("components", []) if component.get("name") in NON_COMMERCIAL_COMPONENTS})
        dist = pathlib.Path(args.web_dist)
        dist_signals = (b"/demo/demo", b"/demo/tree", b"tool/gen", b"workflow/process", b"monitor/snailjob")
        dist_hits = [item.relative_to(dist).as_posix() for item in dist.rglob("*.js") if any(signal in item.read_bytes() for signal in dist_signals)]
        if jar_hits or sbom_hits or dist_hits:
            failures.append(f"商业制品仍含非V1信号: jar={jar_hits} sbom={sbom_hits} web={dist_hits}")
        artifacts = {"jarExcludedLibraries": jar_hits, "sbomExcludedModules": sbom_hits, "webSignalFiles": dist_hits}
    if failures:
        fail("; ".join(failures))

    result = {
        "schemaVersion": "1.0", "gate": "T2-GATE9B-G9A-R2-RUNTIME",
        "findingId": "G9A-ASM-P1-001", "status": "PASS", "acceptedOwners": owner_count,
        "defaultReactorExcludedModules": list(EXCLUDED), "supportComponentExcluded": "ruoyi-common-job",
        "sqlDialects": len(SQL_FILES),
        "artifacts": artifacts,
        "externalExecution": {"providerNetwork":0,"realFunds":0,"realDeviceCommands":0,"realPeripheralCommands":0,"partnerFieldExecution":0,"fullAlpha":0,"productionDeployment":0},
    }
    if args.output:
        output = pathlib.Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"G9A-R2 CONVERGENCE OK: reactor/jar/web/sql/sbom=0 owners={owner_count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
