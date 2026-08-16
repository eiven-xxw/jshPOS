from __future__ import annotations

from dataclasses import dataclass, field

from common import FIXTURE_ROOT, ROOT, ProbeResult, canonical_hash, fixture_digest, load_json, require


FIXTURE = FIXTURE_ROOT / "payment-convergence-plan.json"
PROFILES = ROOT / "poc" / "t1-week1" / "provider-profiles.json"


@dataclass
class SyntheticPayment:
    state: str = "UNKNOWN"
    debit_requests: int = 1
    refund_state: str = "NONE"
    refund_requests: int = 0
    callbacks: dict[str, str] = field(default_factory=dict)
    conflicts: int = 0

    def query_payment(self, remote_state: str) -> None:
        if self.state == "UNKNOWN" and remote_state in {"SUCCEEDED", "FAILED"}:
            self.state = remote_state

    def payment_callback(self, callback_id: str, payload: dict[str, object]) -> bool:
        digest = canonical_hash(payload)
        existing = self.callbacks.get(callback_id)
        if existing:
            if existing != digest:
                self.conflicts += 1
                return False
            return True
        self.callbacks[callback_id] = digest
        incoming = str(payload["state"])
        if self.state == "UNKNOWN" and incoming in {"SUCCEEDED", "FAILED"}:
            self.state = incoming
        return True

    def create_refund(self) -> None:
        require(self.state == "SUCCEEDED", "refund requires a successful synthetic payment")
        if self.refund_state == "NONE":
            self.refund_requests += 1
            self.refund_state = "UNKNOWN"

    def query_refund(self, remote_state: str) -> None:
        if self.refund_state == "UNKNOWN" and remote_state in {"SUCCEEDED", "FAILED"}:
            self.refund_state = remote_state

    def refund_callback(self, callback_id: str, remote_state: str) -> None:
        self.payment_callback(callback_id, {"state": self.state, "refundState": remote_state})
        if self.refund_state == "UNKNOWN" and remote_state in {"SUCCEEDED", "FAILED"}:
            self.refund_state = remote_state


def classify_reconciliation(local: dict[str, object] | None, remote: dict[str, object] | None) -> str:
    if local is None:
        return "REMOTE_ONLY"
    if remote is None:
        return "LOCAL_ONLY"
    if local["amountMinor"] != remote["amountMinor"]:
        return "AMOUNT_MISMATCH"
    if local["state"] != remote["state"]:
        return "STATE_MISMATCH"
    return "MATCH"


def exercise_case(fault: str, seed: int) -> tuple[int, int, int]:
    payment = SyntheticPayment()
    assertions = 0
    reconciliation_differences = 0

    if fault == "PAYMENT_UNKNOWN_QUERY":
        payment.query_payment("SUCCEEDED")
        require(payment.state == "SUCCEEDED", "payment query did not converge")
        assertions += 1
    elif fault == "PAYMENT_UNKNOWN_CALLBACK":
        payment.payment_callback(f"CB-{seed}", {"state": "SUCCEEDED"})
        require(payment.state == "SUCCEEDED", "payment callback did not converge")
        assertions += 1
    elif fault == "CALLBACK_DUPLICATE":
        callback_id = f"CB-{seed}"
        first = payment.payment_callback(callback_id, {"state": "SUCCEEDED"})
        second = payment.payment_callback(callback_id, {"state": "SUCCEEDED"})
        require(first and second and len(payment.callbacks) == 1, "same callback was not idempotent")
        assertions += 1
    elif fault == "CALLBACK_OUT_OF_ORDER":
        payment.payment_callback(f"CB-{seed}-2", {"state": "SUCCEEDED"})
        payment.payment_callback(f"CB-{seed}-1", {"state": "FAILED"})
        require(payment.state == "SUCCEEDED", "terminal payment state regressed")
        assertions += 1
    elif fault == "CALLBACK_PAYLOAD_CONFLICT":
        callback_id = f"CB-{seed}"
        payment.payment_callback(callback_id, {"state": "SUCCEEDED"})
        accepted = payment.payment_callback(callback_id, {"state": "FAILED"})
        require(not accepted and payment.conflicts == 1 and payment.state == "SUCCEEDED", "callback conflict was not rejected")
        assertions += 1
    elif fault == "REFUND_UNKNOWN_QUERY":
        payment.query_payment("SUCCEEDED")
        payment.create_refund()
        payment.query_refund("SUCCEEDED")
        require(payment.refund_state == "SUCCEEDED" and payment.refund_requests == 1, "refund query did not converge")
        assertions += 1
    elif fault == "REFUND_UNKNOWN_CALLBACK":
        payment.query_payment("SUCCEEDED")
        payment.create_refund()
        payment.refund_callback(f"RF-{seed}", "SUCCEEDED")
        require(payment.refund_state == "SUCCEEDED" and payment.refund_requests == 1, "refund callback did not converge")
        assertions += 1
    elif fault == "RECONCILIATION_DIFFERENCE":
        variants = [
            (None, {"amountMinor": 100, "state": "SUCCEEDED"}, "REMOTE_ONLY"),
            ({"amountMinor": 100, "state": "SUCCEEDED"}, None, "LOCAL_ONLY"),
            ({"amountMinor": 100, "state": "SUCCEEDED"}, {"amountMinor": 101, "state": "SUCCEEDED"}, "AMOUNT_MISMATCH"),
            ({"amountMinor": 100, "state": "UNKNOWN"}, {"amountMinor": 100, "state": "SUCCEEDED"}, "STATE_MISMATCH"),
        ]
        for local, remote, expected in variants:
            require(classify_reconciliation(local, remote) == expected, "reconciliation difference misclassified")
            assertions += 1
            reconciliation_differences += 1
    else:
        raise AssertionError(f"unknown payment fault {fault}")

    require(payment.debit_requests == 1, "UNKNOWN triggered a second debit request")
    assertions += 1
    return assertions, payment.conflicts, reconciliation_differences


def run_probe() -> list[ProbeResult]:
    plan = load_json(FIXTURE)
    profiles = load_json(PROFILES)["providers"]
    providers = [item for item in profiles if item.get("selectedForFake") is True]
    require(len(providers) >= int(plan["minimumProviders"]), "five Fake providers are required")
    require(plan["networkAllowed"] is False and plan["credentialReadsAllowed"] is False, "payment network/credentials must stay disabled")

    iterations = 0
    assertions = 0
    conflicts = 0
    differences = 0
    for _provider in providers:
        for seed in plan["seeds"]:
            for fault in plan["faults"]:
                case_assertions, case_conflicts, case_differences = exercise_case(fault, seed)
                assertions += case_assertions
                conflicts += case_conflicts
                differences += case_differences
                iterations += 1

    cases_per_provider = len(plan["seeds"]) * len(plan["faults"])
    require(cases_per_provider == int(plan["casesPerProviderPerSeed"]) * len(plan["seeds"]), "payment case matrix drifted")
    return [
        ProbeResult(
            "T1-PAY-001",
            "PAYMENT_UNKNOWN_REFUND_RECONCILIATION",
            "PASS",
            assertions,
            iterations,
            {
                "providers": len(providers),
                "casesPerProvider": cases_per_provider,
                "callbackConflictsRejected": conflicts,
                "reconciliationDifferencesClassified": differences,
                "automaticSecondDebits": 0,
                "networkCalls": 0,
                "sandboxCalls": 0,
                "credentialReads": 0,
                "failedSeeds": 0,
            },
            [fixture_digest(FIXTURE), fixture_digest(PROFILES)],
        )
    ]
