#!/usr/bin/env python3
"""执行 T2-RDY-001 14 个固定失败关闭向量。"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import pathlib


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts/t2/gate8c-rdy001"


def reject(message: str) -> None:
    raise ValueError(message)


def sha(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate(root: pathlib.Path, context: dict) -> None:
    manifest = context["manifest"]
    artifacts = manifest.get("artifacts", [])
    if len(artifacts) != 10:
        reject("missing artifact")
    ids = [item.get("artifactId") for item in artifacts]
    if len(ids) != len(set(ids)):
        reject("duplicate artifact identity")
    for item in artifacts:
        path = root / item.get("path", "")
        if not path.is_file() or item.get("size") != path.stat().st_size or item.get("sha256") != sha(path):
            reject("artifact digest mismatch")
        if item.get("productionEligible") is not False:
            reject("internal artifact marked production eligible")
    if context.get("signatureVerified") is not True:
        reject("signature invalid")
    if context.get("privateKeyPresent") is True:
        reject("private key present")
    if manifest.get("commitSha") != context["commitSha"] or manifest.get("githubRunId") != context["runId"]:
        reject("commit or run mismatch")
    required_supply = {"SERVER_SBOM", "FLUTTER_SBOM", "WEB_LICENSES"}
    if not required_supply.issubset(ids):
        reject("supply chain evidence missing")
    if context.get("licensePlaceholder") is True:
        reject("internal package license placeholder")
    if context.get("defaultSecretOrUnpinnedImage") is True:
        reject("unsafe deployment configuration")
    if context.get("externalFindingClosed") is True:
        reject("external finding incorrectly closed")
    if context.get("uatOrRelAccepted") is True:
        reject("UAT or REL status drift")
    if not {"MIGRATION_EVIDENCE", "OPERATIONS_EVIDENCE"}.issubset(ids):
        reject("migration or operations evidence missing")
    if any(context.get("externalExecution", {}).values()):
        reject("external execution non-zero")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    root = args.candidate_dir.resolve()
    manifest = json.loads((root / "artifact-manifest.json").read_text(encoding="utf-8"))
    base = {
        "manifest": manifest, "signatureVerified": True, "privateKeyPresent": False,
        "commitSha": manifest["commitSha"], "runId": manifest["githubRunId"],
        "licensePlaceholder": False, "defaultSecretOrUnpinnedImage": False,
        "externalFindingClosed": False, "uatOrRelAccepted": False,
        "externalExecution": {"providerNetworkCalls":0,"realDeviceCommands":0,"productionDeployments":0},
    }
    validate(root, base)
    vectors = json.loads((CONTRACT / "test-vectors-v1.json").read_text(encoding="utf-8"))["vectors"]
    observed = []
    for vector in vectors:
        context = copy.deepcopy(base)
        case = vector["case"]
        artifacts = context["manifest"]["artifacts"]
        if case == "MISSING_ARTIFACT": artifacts.pop()
        elif case == "DUPLICATE_ARTIFACT_ID": artifacts.append(copy.deepcopy(artifacts[0]))
        elif case == "DIGEST_MISMATCH": artifacts[0]["sha256"] = "0" * 64
        elif case == "SIGNATURE_INVALID": context["signatureVerified"] = False
        elif case == "PRIVATE_KEY_IN_ARTIFACT": context["privateKeyPresent"] = True
        elif case == "COMMIT_OR_RUN_MISMATCH": context["runId"] = "0"
        elif case == "SBOM_OR_LICENSE_MISSING":
            context["manifest"]["artifacts"] = [item for item in artifacts if item["artifactId"] != "SERVER_SBOM"]
        elif case == "PACKAGE_LICENSE_PLACEHOLDER": context["licensePlaceholder"] = True
        elif case == "DEFAULT_SECRET_OR_UNPINNED_IMAGE": context["defaultSecretOrUnpinnedImage"] = True
        elif case == "DEBUG_APK_MARKED_PRODUCTION":
            next(item for item in artifacts if item["artifactId"] == "POS_DEBUG_APK")["productionEligible"] = True
        elif case == "EXTERNAL_BLOCKER_MARKED_CLOSED": context["externalFindingClosed"] = True
        elif case == "UAT_OR_REL_MARKED_ACCEPTED": context["uatOrRelAccepted"] = True
        elif case == "RESTORE_OR_ROLLBACK_EVIDENCE_MISSING":
            context["manifest"]["artifacts"] = [item for item in artifacts if item["artifactId"] != "OPERATIONS_EVIDENCE"]
        elif case == "EXTERNAL_EXECUTION_NON_ZERO": context["externalExecution"]["productionDeployments"] = 1
        else: reject("unknown vector " + case)
        rejected = False
        reason = ""
        try:
            validate(root, context)
        except ValueError as error:
            rejected = True
            reason = str(error)
        if not rejected:
            raise SystemExit("T2-RDY-001 FAULT ERROR: vector accepted " + vector["id"])
        observed.append({"id":vector["id"],"case":case,"expected":"FAIL_CLOSED","observed":"FAIL_CLOSED","reason":reason})
    result = {
        "schemaVersion":"1.0","gate":"T2-GATE8C-SPRINT-S26D","status":"PASS",
        "requirementId":"T2-RDY-001","seedCount":len(observed),"failedSeeds":[],
        "vectors":observed,"automaticRetries":0,"commercialClaimAllowed":False,
    }
    target = args.output if args.output.is_absolute() else ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("T2-RDY-001 FAULTS OK: vectors=14 failedSeeds=0")


if __name__ == "__main__":
    main()
