from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path


POC_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(POC_ROOT / "src"))

import t1_fake_harness as harness  # noqa: E402


class T1FakeHarnessTest(unittest.TestCase):
    def test_all_seven_risk_probes_pass(self) -> None:
        results = harness.run_all()
        self.assertEqual(7, len(results))
        self.assertEqual({"PASS"}, {result.result for result in results})
        self.assertEqual(
            {
                "T1-HWD-001",
                "T1-PAY-001",
                "T1-OFF-001",
                "T1-SYN-001",
                "T1-TEN-001",
                "T1-DPK-001",
                "T1-UPG-001",
            },
            {result.requirementId for result in results},
        )

    def test_payment_fake_runs_exactly_five_of_ten_candidates(self) -> None:
        document = json.loads((POC_ROOT / "provider-profiles.json").read_text(encoding="utf-8"))
        providers = document["providers"]
        self.assertEqual(10, len(providers))
        self.assertEqual(5, sum(profile["selectedForFake"] for profile in providers))
        self.assertTrue(all(profile["sandboxStatus"] == "BLOCKED" for profile in providers))
        self.assertTrue(all(profile["integrationStatus"] == "CANDIDATE_ONLY" for profile in providers))

    def test_evidence_cannot_claim_external_validation(self) -> None:
        evidence = harness.build_evidence(harness.run_all())
        self.assertEqual("FAKE", evidence["evidenceLevel"])
        encoded = json.dumps(evidence, ensure_ascii=False)
        for forbidden in ("REAL_DEVICE", "SANDBOX_PASS", "PILOT_PASS", "COMMERCIAL_PASS"):
            self.assertNotIn(forbidden, encoded)

    def test_offline_model_is_zero_or_all(self) -> None:
        result = harness.probe_offline_atomicity()
        self.assertEqual("PASS", result.result)
        self.assertEqual(5, result.assertions)

    def test_duplicate_and_out_of_order_sync_converges(self) -> None:
        result = harness.probe_sync_idempotency()
        self.assertEqual("PASS", result.result)
        self.assertGreaterEqual(result.assertions, 12)


if __name__ == "__main__":
    unittest.main()
