from __future__ import annotations

import argparse
import ast
import hashlib
import json
import re
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
BASELINE_COMMIT = "04b176f2cd44ae4738a7bfd855548b17fa1bd380"
DEPENDENCY_MANIFESTS = {
    "pom.xml", "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
    "pubspec.yaml", "pubspec.lock", "build.gradle", "build.gradle.kts",
    "settings.gradle", "settings.gradle.kts", "libs.versions.toml",
}


def fail(message: str) -> None:
    print(f"T1 INVENTORY ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args], cwd=ROOT, check=False, capture_output=True, text=True,
        encoding="utf-8", errors="replace",
    )
    if completed.returncode != 0:
        fail(f"git {' '.join(args)} failed: {completed.stderr.strip()}")
    return completed.stdout.strip()


def write_json(path: Path, document: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def poc_python_imports() -> tuple[list[str], int]:
    local_modules = {path.stem for path in ROOT.glob("poc/t1-week*/src/*.py")}
    third_party: set[str] = set()
    files = sorted(ROOT.glob("poc/t1-week*/src/*.py")) + sorted(ROOT.glob("poc/t1-week*/tests/*.py"))
    for path in files:
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        imports: set[str] = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imports.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imports.add(node.module.split(".")[0])
        third_party.update(imports - set(sys.stdlib_module_names) - local_modules)
    return sorted(third_party), len(files)


def changed_dependency_manifests() -> list[str]:
    changed = git("-c", "core.quotepath=false", "diff", "--name-only", BASELINE_COMMIT, "HEAD").splitlines()
    return sorted(path for path in changed if Path(path).name in DEPENDENCY_MANIFESTS)


def action_inventory() -> list[dict[str, str]]:
    pattern = re.compile(r"^\s*-\s+uses:\s+([^@\s]+)@([0-9a-f]{40})", re.MULTILINE)
    inventory: dict[tuple[str, str], set[str]] = {}
    for path in sorted((ROOT / ".github" / "workflows").glob("t1-week*.yml")):
        for action, revision in pattern.findall(path.read_text(encoding="utf-8")):
            inventory.setdefault((action, revision), set()).add(path.name)
    return [
        {"action": action, "revision": revision, "workflows": ",".join(sorted(workflows)), "distribution": "CI_TOOLING_NOT_SHIPPED"}
        for (action, revision), workflows in sorted(inventory.items())
    ]


def scan_sensitive_and_shortcuts() -> dict[str, int]:
    secret_patterns = [
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
        re.compile(r"AKIA[0-9A-Z]{16}"),
        re.compile(r"\bsk-[A-Za-z0-9]{20,}\b"),
    ]
    pii_patterns = [
        re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"),
        re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)"),
        re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
    ]
    shortcut_patterns = [
        re.compile(r"\bTODO\b"), re.compile(r"\bFIXME\b"), re.compile(r"\bHACK\b"),
        re.compile(r"@unittest\.skip"), re.compile(r"pytest\.mark\.skip"),
    ]
    files = sorted(ROOT.glob("poc/t1-week*/**/*"))
    text_files = [path for path in files if path.is_file() and path.suffix.lower() in {".py", ".json", ".md"}]
    secrets = pii = shortcuts = 0
    for path in text_files:
        content = path.read_text(encoding="utf-8")
        secrets += sum(len(pattern.findall(content)) for pattern in secret_patterns)
        pii += sum(len(pattern.findall(content)) for pattern in pii_patterns)
        shortcuts += sum(len(pattern.findall(content)) for pattern in shortcut_patterns)
    return {"filesScanned": len(text_files), "secretMatches": secrets, "piiMatches": pii, "shortcutMatches": shortcuts}


def main() -> None:
    parser = argparse.ArgumentParser(description="Build T1 PoC SBOM, license and security inventory")
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    commit = git("rev-parse", "HEAD")
    generated = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    third_party, python_files = poc_python_imports()
    manifest_changes = changed_dependency_manifests()
    actions = action_inventory()
    security = scan_sensitive_and_shortcuts()
    if third_party or manifest_changes or any(security[key] for key in ("secretMatches", "piiMatches", "shortcutMatches")):
        fail(
            f"inventory gate failed: thirdParty={third_party} manifests={manifest_changes} security={security}"
        )
    if not actions or any(not re.fullmatch(r"[0-9a-f]{40}", item["revision"]) for item in actions):
        fail("T1 GitHub Actions inventory is empty or unpinned")

    sbom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{uuid.uuid5(uuid.NAMESPACE_URL, 'jshpos-t1-poc-' + commit)}",
        "version": 1,
        "metadata": {
            "timestamp": generated,
            "component": {
                "type": "application",
                "bom-ref": f"jshpos-t1-risk-poc@{commit}",
                "name": "jshpos-t1-risk-poc",
                "version": commit,
                "properties": [
                    {"name": "jshpos:evidence-levels", "value": "STATIC,FAKE"},
                    {"name": "jshpos:distribution", "value": "NON_PRODUCTION_RISK_POC"},
                    {"name": "jshpos:python-runtime", "value": "STANDARD_LIBRARY_ONLY"},
                ],
            },
        },
        "components": [],
        "dependencies": [{"ref": f"jshpos-t1-risk-poc@{commit}", "dependsOn": []}],
    }
    license_inventory = {
        "schemaVersion": "1.0",
        "phase": "T1-WEEK4",
        "generatedAt": generated,
        "commitSha": commit,
        "runtimeThirdPartyDependencies": third_party,
        "changedDependencyManifestsSinceT0": manifest_changes,
        "pythonSourceAndTestFiles": python_files,
        "ciTooling": actions,
        "ciToolingPolicy": "固定SHA的GitHub Actions只用于CI且不随产品发布；产品依赖许可证仍沿用T0 SBOM和ADR-015门禁",
        "commercialReleaseBlockersUnchanged": ["Aviator", "simple-http", "MySQL Connector/J"],
    }
    security_summary = {
        "schemaVersion": "1.0",
        "phase": "T1-WEEK4",
        "evidenceLevel": "STATIC",
        "generatedAt": generated,
        "commitSha": commit,
        "scope": "T1_POC_ONLY",
        "scanSummary": security,
        "networkClientsAdded": 0,
        "credentialReadsAdded": 0,
        "nonSyntheticTablesAdded": 0,
        "thirdPartyPythonImports": third_party,
        "changedDependencyManifests": manifest_changes,
        "limitations": [
            "本摘要覆盖T1 PoC源文件和夹具，不替代商业V1全仓库安全评估",
            "Trivy漏洞、Secret和Workflow HIGH/CRITICAL扫描由Week 4 CI独立执行",
            "没有真实支付凭据、设备证书、商户数据或生产Secret进入T1",
        ],
    }
    write_json(args.output_dir / "t1-poc-sbom.cdx.json", sbom)
    write_json(args.output_dir / "t1-license-inventory.json", license_inventory)
    write_json(args.output_dir / "t1-security-summary.json", security_summary)
    print(
        "T1 INVENTORY OK: "
        f"pythonFiles={python_files} runtimeThirdParty=0 dependencyManifestChanges=0 "
        f"actions={len(actions)} secrets=0 pii=0 shortcuts=0"
    )


if __name__ == "__main__":
    main()
