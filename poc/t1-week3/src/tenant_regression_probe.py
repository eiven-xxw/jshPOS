from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

from common import ROOT, ProbeResult, fixture_digest, require


WEEK2_HARNESS = ROOT / "poc" / "t1-week2" / "src" / "t1_week2_harness.py"
WEEK2_FIXTURE = ROOT / "poc" / "t1-week2" / "fixtures" / "tenant-attack-plan.json"


def run_probe() -> list[ProbeResult]:
    with tempfile.TemporaryDirectory(prefix="jshpos-w3-tenant-") as directory:
        evidence_path = Path(directory) / "week2-tenant-regression.json"
        completed = subprocess.run(
            [sys.executable, str(WEEK2_HARNESS), "--domains", "tenant", "--output", str(evidence_path)],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        require(completed.returncode == 0, f"Week 2 tenant regression failed: {completed.stderr}")
        document = json.loads(evidence_path.read_text(encoding="utf-8"))
        require(document["evidenceLevel"] == "FAKE" and len(document["results"]) == 1, "tenant evidence level/result drifted")
        result = document["results"][0]
        require(result["requirementId"] == "T1-TEN-001" and result["result"] == "PASS", "tenant attack regression did not pass")
        metrics = dict(result["metrics"])
        metrics.update({"week2ProbeReusedUnchanged": 1, "failedSeeds": 0})
        return [
            ProbeResult(
                "T1-TEN-001",
                "TENANT_ATTACK_REGRESSION",
                "PASS",
                int(result["assertions"]),
                int(result["iterations"]),
                metrics,
                [fixture_digest(WEEK2_FIXTURE)],
            )
        ]
