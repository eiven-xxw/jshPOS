#!/usr/bin/env python3
"""G9A-R2 前向清理预检的签名、漂移和过期失败关闭回归。"""
from __future__ import annotations

import datetime as dt
import hashlib
import hmac
import importlib.util
import json
import os
import pathlib
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("cleanup", ROOT / "scripts/build_t2_g9a_r2_forward_cleanup.py")
cleanup = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(cleanup)


class ForwardCleanupPreflightTest(unittest.TestCase):
    def setUp(self) -> None:
        self.key = "synthetic-g9a-r2-test-key-32-bytes-minimum"
        os.environ[cleanup.KEY_ENV] = self.key
        self.policy = json.loads(cleanup.POLICY.read_text(encoding="utf-8"))
        self.now = dt.datetime(2026, 8, 24, 8, 10, tzinfo=dt.timezone.utc)
        self.document = {
            "schemaVersion": "1.0", "findingId": "G9A-ASM-P1-001", "dialect": "mysql",
            "environmentRef": "synthetic-env-001", "observedAt": "2026-08-24T08:00:00Z",
            "expiresAt": "2026-08-24T08:20:00Z", "snapshotSha256": "a" * 64,
            "targetMenuCount": 24, "targetRoleBindingCount": 26, "demoTableCount": 2,
            "demoRowCount": 26, "targetMenuMismatchCount": 0, "nonBaselineRoleBindingCount": 0,
            "nonBaselineDemoRowCount": 0, "ownerFactReferenceCount": 0, "schemaDriftCount": 0,
            "custodian": "synthetic-dba", "signatureAlgorithm": "HMAC-SHA256"
        }
        self.sign()

    def sign(self) -> None:
        self.document["signature"] = hmac.new(self.key.encode(), cleanup.canonical(self.document), hashlib.sha256).hexdigest()

    def test_valid_signed_preflight_generates_only_frozen_targets(self) -> None:
        cleanup.validate(self.document, self.policy, self.now)
        sql = cleanup.sql_for("mysql", self.policy["targetMenuIds"])
        self.assertIn("DELETE FROM sys_role_menu", sql)
        self.assertIn("DROP TABLE IF EXISTS test_demo", sql)
        self.assertNotIn("jsh_", sql)

    def test_unsigned_tamper_fails_closed(self) -> None:
        self.document["demoRowCount"] = 27
        with self.assertRaisesRegex(ValueError, "签名无效"):
            cleanup.validate(self.document, self.policy, self.now)

    def test_signed_nonbaseline_data_still_fails_closed(self) -> None:
        self.document["nonBaselineDemoRowCount"] = 1
        self.sign()
        with self.assertRaisesRegex(ValueError, "nonBaselineDemoRowCount"):
            cleanup.validate(self.document, self.policy, self.now)

    def test_expired_preflight_fails_closed(self) -> None:
        later = dt.datetime(2026, 8, 24, 8, 30, tzinfo=dt.timezone.utc)
        with self.assertRaisesRegex(ValueError, "过期"):
            cleanup.validate(self.document, self.policy, later)


if __name__ == "__main__":
    unittest.main()
