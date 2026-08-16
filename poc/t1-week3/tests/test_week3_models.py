from __future__ import annotations

import sys
import unittest
from pathlib import Path


SRC = Path(__file__).resolve().parents[1] / "src"
sys.path.insert(0, str(SRC))

from common import failed_seed_summary  # noqa: E402
from package_recovery_probe import validate_candidate  # noqa: E402
from payment_convergence_probe import SyntheticPayment, classify_reconciliation  # noqa: E402


class Week3ModelTests(unittest.TestCase):
    def test_unknown_never_creates_second_debit(self) -> None:
        payment = SyntheticPayment()
        payment.query_payment("SUCCEEDED")
        self.assertEqual("SUCCEEDED", payment.state)
        self.assertEqual(1, payment.debit_requests)

    def test_callback_conflict_is_rejected(self) -> None:
        payment = SyntheticPayment()
        self.assertTrue(payment.payment_callback("CB-1", {"state": "SUCCEEDED"}))
        self.assertFalse(payment.payment_callback("CB-1", {"state": "FAILED"}))
        self.assertEqual("SUCCEEDED", payment.state)

    def test_reconciliation_classification(self) -> None:
        local = {"amountMinor": 100, "state": "SUCCEEDED"}
        remote = {"amountMinor": 101, "state": "SUCCEEDED"}
        self.assertEqual("AMOUNT_MISMATCH", classify_reconciliation(local, remote))

    def test_package_tenant_and_replay_fail_closed(self) -> None:
        placeholder = Path(__file__)
        digest = __import__("hashlib").sha256(placeholder.read_bytes()).hexdigest()
        self.assertEqual("CROSS_TENANT_REJECTED", validate_candidate(placeholder, digest, "B", "A", 2, 1))
        self.assertEqual("REPLAY_REJECTED", validate_candidate(placeholder, digest, "A", "A", 1, 1))

    def test_failed_seed_ledger_has_no_untracked_failure(self) -> None:
        self.assertEqual(0, failed_seed_summary()["untracked"])


if __name__ == "__main__":
    unittest.main()
