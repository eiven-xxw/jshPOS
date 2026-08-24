#!/usr/bin/env python3
"""装配 T2-RDY-001 内部发布准备候选、发布 BOM 与机器可读 NO-GO。"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import pathlib
import shutil
import tarfile
from typing import Iterable


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate8c-rdy001"


def fail(message: str) -> None:
    raise SystemExit("T2-RDY-001 RELEASE ERROR: " + message)


def load(path: pathlib.Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"invalid JSON {path}: {error}")


def one(root: pathlib.Path, pattern: str) -> pathlib.Path:
    matches = [path for path in root.rglob(pattern) if path.is_file()]
    if len(matches) != 1:
        fail(f"expected exactly one {root.name}/{pattern}, got {len(matches)}")
    return matches[0]


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def copy(source: pathlib.Path, target: pathlib.Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def archive(paths: Iterable[tuple[pathlib.Path, str]], target: pathlib.Path) -> None:
    """生成 mtime/owner 固定的 gzip tar，避免同内容摘要随执行器漂移。"""
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as tar:
                for source, archive_root in sorted(paths, key=lambda item: item[1]):
                    candidates = [source] if source.is_file() else sorted(path for path in source.rglob("*") if path.is_file())
                    for candidate in candidates:
                        relative = candidate.name if source.is_file() else candidate.relative_to(source).as_posix()
                        name = f"{archive_root}/{relative}"
                        info = tar.gettarinfo(str(candidate), arcname=name)
                        info.uid = info.gid = 0
                        info.uname = info.gname = ""
                        info.mtime = 0
                        info.mode = 0o644
                        with candidate.open("rb") as data:
                            tar.addfile(info, data)


def validate_deployment() -> dict:
    profile = load(CONTRACT / "deployment-profile-v1.json")
    application = (ROOT / "infra/internal-rc/application-internal-rc.yml").read_text(encoding="utf-8")
    runtime_example = (ROOT / "infra/internal-rc/runtime.env.example").read_text(encoding="utf-8")
    compose = (ROOT / "infra/compose/compose.yaml").read_text(encoding="utf-8")
    missing = [name for name in profile["requiredSecretReferences"] if "${" + name + "}" not in application]
    if missing:
        fail("internal runtime missing Secret references: " + ", ".join(missing))
    if "include: '*'" in application or "show-details: ALWAYS" in application:
        fail("unsafe actuator configuration")
    images = [line.split("image:", 1)[1].strip() for line in compose.splitlines() if "image:" in line]
    if len(images) != 2 or any("@sha256:" not in image for image in images):
        fail("container images must be digest pinned")
    forbidden = ["privileged: true", "network_mode: host", "MYSQL_ROOT_PASSWORD: root"]
    if any(marker in compose for marker in forbidden):
        fail("unsafe compose configuration")
    if runtime_example.count("replace-in-secret-system") < 3:
        fail("runtime Secret example must fail closed with explicit placeholders")
    return {
        "status": "PASS", "classification": profile["classification"],
        "requiredSecretReferences": profile["requiredSecretReferences"], "pinnedImages": images,
        "realKms": profile["realKms"], "realObjectStorage": profile["realObjectStorage"],
        "realPitr": profile["realPitr"], "crossRegionDisasterRecovery": profile["crossRegionDisasterRecovery"],
        "productionDeployAllowed": False, "unauthorizedCloudWrites": 0,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output-dir", required=True, type=pathlib.Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--release-notes", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if len(args.commit) != 40 or any(char not in "0123456789abcdef" for char in args.commit):
        fail("invalid commit SHA")
    if not args.run_id.isdigit():
        fail("invalid GitHub Run ID")
    bundle = args.bundle_dir.resolve()
    output = args.output_dir.resolve()
    catalog = load(CONTRACT / "artifact-catalog-v1.json")
    signing = load(CONTRACT / "signing-policy-v1.json")
    decisions = load(CONTRACT / "go-no-go-v1.json")
    disposition = load(CONTRACT / "findings-disposition.json")
    license_plan = load(ROOT / "contracts/t2/gate7f-prep/license-closure-plan.json")
    if license_plan["closedComponentCount"] != 0 or license_plan["commercialReleaseDecision"] != "NO_GO":
        fail("T2-LIC-001 closure state drift")
    package_license = (ROOT / "packages/pos_device_adapter/LICENSE").read_text(encoding="utf-8")
    package_changelog = (ROOT / "packages/pos_device_adapter/CHANGELOG.md").read_text(encoding="utf-8")
    if "TODO" in package_license or "TODO" in package_changelog or "INTERNAL PROPRIETARY" not in package_license:
        fail("pos_device_adapter ownership notice remains a placeholder")

    release_id = f"jshpos-internal-readiness-{args.commit[:12]}-{args.run_id}"
    sources = {
        "serverJar": one(bundle / "server", "ruoyi-admin.jar"),
        "serverSbom": one(bundle / "server", "bom.json"),
        "webIndex": one(bundle / "web", "index.html"),
        "webLicenses": one(bundle / "web", "licenses.json"),
        "posApk": one(bundle / "pos", "app-debug.apk"),
        "flutterSbom": one(bundle / "pos", "flutter-cyclonedx.json"),
        "mysqlEvidence": one(bundle / "mysql", "mysql-migration.json"),
        "operationsEvidence": one(bundle / "operations", "operations-evidence.json"),
    }
    web_root = sources["webIndex"].parent
    targets = {
        "SERVER_JAR": output / "server/ruoyi-admin.jar",
        "ADMIN_WEB_BUNDLE": output / "web/admin-web.tar.gz",
        "POS_DEBUG_APK": output / "pos/jshpos-internal-debug.apk",
        "DEPLOYMENT_BUNDLE": output / "deployment/internal-deployment.tar.gz",
        "SERVER_SBOM": output / "supply-chain/server-bom.json",
        "FLUTTER_SBOM": output / "supply-chain/flutter-cyclonedx.json",
        "WEB_LICENSES": output / "supply-chain/web-licenses.json",
        "MIGRATION_EVIDENCE": output / "evidence/mysql-migration.json",
        "OPERATIONS_EVIDENCE": output / "evidence/operations-evidence.json",
        "RELEASE_NOTES": output / "release-notes.md",
    }
    output.mkdir(parents=True, exist_ok=True)
    copy(sources["serverJar"], targets["SERVER_JAR"])
    archive([(web_root, "admin-web")], targets["ADMIN_WEB_BUNDLE"])
    copy(sources["posApk"], targets["POS_DEBUG_APK"])
    archive([
        (ROOT / "infra/compose", "infra/compose"),
        (ROOT / "infra/internal-rc", "infra/internal-rc"),
        (ROOT / "docs/t2-gate8c-rdy001/03_部署配置许可证与运维证据设计.md", "docs"),
    ], targets["DEPLOYMENT_BUNDLE"])
    copy(sources["serverSbom"], targets["SERVER_SBOM"])
    copy(sources["flutterSbom"], targets["FLUTTER_SBOM"])
    copy(sources["webLicenses"], targets["WEB_LICENSES"])
    copy(sources["mysqlEvidence"], targets["MIGRATION_EVIDENCE"])
    copy(sources["operationsEvidence"], targets["OPERATIONS_EVIDENCE"])
    copy(args.release_notes.resolve(), targets["RELEASE_NOTES"])

    catalog_by_id = {item["artifactId"]: item for item in catalog["requiredArtifacts"]}
    if set(catalog_by_id) != set(targets):
        fail("catalog and builder artifact identities differ")
    artifacts = []
    for artifact_id, path in targets.items():
        contract = catalog_by_id[artifact_id]
        relative = path.relative_to(output).as_posix()
        if relative != contract["sourcePattern"]:
            fail(f"catalog path drift for {artifact_id}: {relative}")
        artifacts.append({
            "releaseId": release_id, "commitSha": args.commit, "githubRunId": args.run_id,
            "artifactId": artifact_id, "path": relative, "size": path.stat().st_size,
            "sha256": sha256(path), "mediaType": contract["mediaType"],
            "productionEligible": False,
        })
    manifest = {
        "schemaVersion": "1.0", "requirementId": "T2-RDY-001", "status": "PASS",
        "classification": catalog["classification"], "releaseId": release_id,
        "commitSha": args.commit, "githubRunId": args.run_id, "sameRunEvidence": True,
        "artifactCount": len(artifacts), "artifacts": sorted(artifacts, key=lambda item: item["artifactId"]),
        "manifestSigning": {
            "algorithm": signing["manifestAlgorithm"], "keyClass": signing["keyClass"],
            "privateKeyArtifactAllowed": False, "productionApkSigning": signing["productionApkSigning"],
        },
        "externalExecution": {
            "providerNetworkCalls": 0, "realFunds": 0, "realDeviceCommands": 0,
            "realPeripheralCommands": 0, "partnerOnsite": 0, "fullAlpha": 0,
            "productionDeployments": 0, "commercialTags": 0,
        },
        "productionEligible": False,
    }
    canonical = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    (output / "artifact-manifest.canonical.json").write_text(canonical, encoding="utf-8")
    manifest["canonicalSha256"] = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    (output / "artifact-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    deployment = validate_deployment()
    (output / "deployment/preflight.json").parent.mkdir(parents=True, exist_ok=True)
    (output / "deployment/preflight.json").write_text(json.dumps(deployment, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    release_bom = {
        "schemaVersion": "1.0", "releaseId": release_id, "commitSha": args.commit,
        "componentEvidence": [
            {"type":"SERVER_CYCLONEDX","path":"supply-chain/server-bom.json","sha256":sha256(targets["SERVER_SBOM"])},
            {"type":"FLUTTER_CYCLONEDX","path":"supply-chain/flutter-cyclonedx.json","sha256":sha256(targets["FLUTTER_SBOM"])},
            {"type":"WEB_LICENSE_INVENTORY","path":"supply-chain/web-licenses.json","sha256":sha256(targets["WEB_LICENSES"])},
            {"type":"INTERNAL_PACKAGE_NOTICE","path":"packages/pos_device_adapter/LICENSE","sha256":hashlib.sha256(package_license.encode()).hexdigest()},
        ],
        "thirdPartyLicenseClosure": {"closed": 0, "required": 3, "status": "DEFERRED_NO_GO"},
    }
    (output / "release-bom.json").write_text(json.dumps(release_bom, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    decision = {
        "schemaVersion": "1.0", "requirementId": "T2-RDY-001", "releaseId": release_id,
        "internalReleaseReadiness": "GO_INTERNAL_RELEASE_READINESS",
        "fullAlpha": decisions["decisions"]["fullAlpha"],
        "production": decisions["decisions"]["production"],
        "commercial": decisions["decisions"]["commercial"],
        "licenseClosure": decisions["licenseClosure"], "findingsDisposition": disposition["findings"],
        "productionEligible": False,
    }
    (output / "release-decision.json").write_text(json.dumps(decision, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    provenance = {
        "schemaVersion": "1.0", "releaseId": release_id, "commitSha": args.commit,
        "githubRunId": args.run_id, "builder": "scripts/build_t2_gate8c_rdy001_release.py",
        "sourceBaseline": "721130ab57a2fe2b2f024150d85e237491e5b34c",
        "syntheticOnly": True, "productionEligible": False,
    }
    (output / "provenance.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    files = sorted(path for path in output.rglob("*") if path.is_file() and path.name != "SHA256SUMS")
    (output / "SHA256SUMS").write_text("".join(f"{sha256(path)}  {path.relative_to(output).as_posix()}\n" for path in files), encoding="utf-8")
    print(f"T2-RDY-001 RELEASE OK: release={release_id} artifacts={len(artifacts)} production=NO_GO commercial=NO_GO")


if __name__ == "__main__":
    main()
