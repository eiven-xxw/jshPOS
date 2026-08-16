from __future__ import annotations

import copy
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path


POC_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(POC_ROOT / "src"))

from common import FIXTURE_ROOT, load_json  # noqa: E402
from data_package_probe import make_manifest, resign, validate_package, write_records  # noqa: E402
from inbox_probe import InboxStore, event, initialize as initialize_inbox  # noqa: E402
from payment_regression import evaluate_case  # noqa: E402
from tenant_probe import SyntheticTenantBoundary  # noqa: E402
from upgrade_probe import app_can_open, package_preflight  # noqa: E402


class Week2ModelTest(unittest.TestCase):
    def test_inbox_duplicate_out_of_order_and_conflict(self) -> None:
        connection = sqlite3.connect(":memory:")
        initialize_inbox(connection)
        store = InboxStore(connection)
        store.deliver_many([event(3), event(1), event(2), event(2)])
        conflict = event(2, "-DIFFERENT")
        store.deliver_many([conflict])
        self.assertEqual(3, store.cursor())
        self.assertEqual(3, connection.execute("SELECT COUNT(*) FROM syn_effect").fetchone()[0])
        self.assertEqual(1, connection.execute("SELECT COUNT(*) FROM syn_conflict_audit").fetchone()[0])
        connection.close()

    def test_payment_matrix_expectations_are_provider_neutral(self) -> None:
        plan = load_json(FIXTURE_ROOT / "payment-matrix.json")
        for category in plan["categories"]:
            for variant in range(plan["variantsPerCategory"]):
                self.assertEqual(category["expected"], evaluate_case(category["id"], variant))

    def test_data_package_tamper_and_tenant_swap_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="jshpos-t1-w2-unit-") as directory:
            path = Path(directory) / "records.jsonl"
            write_records(path, 10)
            manifest = make_manifest(path, "SYN-PKG-UNIT", "FULL", 1, None, 10)
            self.assertEqual((True, "OK"), validate_package(path, manifest, 0))
            bad_digest = copy.deepcopy(manifest)
            bad_digest["contentSha256"] = "f" * 64
            bad_digest = resign(bad_digest)
            self.assertEqual((False, "DIGEST"), validate_package(path, bad_digest, 0))
            other_tenant = copy.deepcopy(manifest)
            other_tenant["tenantId"] = "TENANT_BETA"
            other_tenant = resign(other_tenant)
            self.assertEqual((False, "TENANT"), validate_package(path, other_tenant, 0))

    def test_tenant_attack_returns_generic_denial_and_audit(self) -> None:
        boundary = SyntheticTenantBoundary()
        try:
            result = boundary.attack(
                "TEN-W2-UNIT",
                "RAW_SQL",
                "TENANT_ALPHA",
                "TENANT_BETA",
                "missing_bound_tenant",
            )
            self.assertEqual("DENY", result.decision)
            self.assertEqual("NOT_FOUND", result.response)
            self.assertEqual(1, boundary.audit_count())
        finally:
            boundary.close()

    def test_upgrade_compatibility_and_preflight_fail_closed(self) -> None:
        supported = [{"app": 1, "minSchema": 1, "maxSchema": 2}, {"app": 2, "minSchema": 2, "maxSchema": 3}]
        self.assertTrue(app_can_open(1, 2, supported))
        self.assertFalse(app_can_open(1, 3, supported))
        for fault in ("DOWNLOAD_TRUNCATED", "BAD_DIGEST", "BAD_TEST_MAC", "SPACE_REJECTED"):
            self.assertFalse(package_preflight(fault))


if __name__ == "__main__":
    unittest.main()
