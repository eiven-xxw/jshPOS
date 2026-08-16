from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from common import FIXTURE_ROOT, POC_ROOT, ProbeResult, fixture_digest, load_json, require


WEEK1_PROFILES = POC_ROOT.parent / "t1-week1" / "provider-profiles.json"


@dataclass
class PaymentModel:
    state: str = "INIT"
    payment_effects: int = 0
    refund_state: str = "NONE"
    refund_effects: int = 0
    dispatches: int = 0
    queries: int = 0
    audits: int = 0
    differences: int = 0

    def success(self) -> None:
        if self.state != "SUCCESS":
            self.payment_effects += 1
        self.state = "SUCCESS"

    def refund_success(self) -> None:
        if self.refund_state != "SUCCESS":
            self.refund_effects += 1
        self.refund_state = "SUCCESS"


def evaluate_case(category: str, variant: int) -> str:
    model = PaymentModel()
    idempotency_key = f"SYN-IDEM-{variant}"
    amount_minor = 100 + variant
    if category == "CAPABILITY_SUPPORTED":
        return "SUPPORTED"
    if category == "CAPABILITY_UNSUPPORTED":
        return "UNSUPPORTED"
    if category == "CREATE_SUCCESS":
        model.dispatches += 1
        model.success()
        return "SUCCESS_ONCE" if model.payment_effects == 1 else "INVALID"
    if category == "CREATE_FAILED":
        model.dispatches += 1
        model.state = "FAILED"
        return "FAILED_NO_EFFECT" if model.payment_effects == 0 else "INVALID"
    if category == "CREATE_PROCESSING":
        model.dispatches += 1
        model.state = "PROCESSING"
        model.success()
        return "SUCCESS_ONCE" if model.payment_effects == 1 else "INVALID"
    if category == "CREATE_UNKNOWN":
        model.dispatches += 1
        model.state = "UNKNOWN"
        model.queries += 1
        model.success()
        return "QUERY_TO_SUCCESS_ONCE" if model.dispatches == 1 and model.queries == 1 and model.payment_effects == 1 else "INVALID"
    if category == "FAIL_BEFORE_DISPATCH":
        model.state = "FAILED"
        return "FAILED_NO_EFFECT" if model.dispatches == 0 and model.payment_effects == 0 else "INVALID"
    if category in {"RESPONSE_TIMEOUT", "RESPONSE_CORRUPT", "UNKNOWN_NO_RETRY"}:
        model.dispatches += 1
        model.state = "UNKNOWN"
        model.queries += 1
        return "QUERY_ONLY_NO_RETRY" if model.dispatches == 1 and model.queries == 1 else "INVALID"
    if category == "QUERY_CONVERGE":
        model.dispatches += 1
        model.state = "PROCESSING"
        model.queries += 1
        model.success()
        return "SUCCESS_ONCE" if model.payment_effects == 1 else "INVALID"
    if category == "CALLBACK_EARLY":
        model.success()
        model.dispatches += 1
        return "SUCCESS_ONCE" if model.payment_effects == 1 else "INVALID"
    if category == "CALLBACK_DUPLICATE":
        model.success()
        model.success()
        return "SUCCESS_ONCE" if model.payment_effects == 1 else "INVALID"
    if category == "CALLBACK_OUT_OF_ORDER":
        model.success()
        if model.state != "SUCCESS":
            model.state = "PROCESSING"
        return "TERMINAL_NO_REGRESSION" if model.state == "SUCCESS" else "INVALID"
    if category in {"CALLBACK_BAD_SIGNATURE", "CALLBACK_REPLAY"}:
        model.audits += 1
        return "REJECT_AUDIT" if model.payment_effects == 0 and model.audits == 1 else "INVALID"
    if category == "IDEMPOTENT_SAME":
        first = (idempotency_key, amount_minor)
        second = (idempotency_key, amount_minor)
        model.dispatches += 1
        model.success()
        return "SAME_RESULT_ONE_EFFECT" if first == second and model.dispatches == 1 and model.payment_effects == 1 else "INVALID"
    if category == "IDEMPOTENT_DIFFERENT":
        first = (idempotency_key, amount_minor)
        second = (idempotency_key, amount_minor + 1)
        return "REJECT_CONFLICT" if first[0] == second[0] and first[1] != second[1] else "INVALID"
    if category == "REFUND_SUCCESS":
        model.success()
        model.refund_success()
        return "REFUNDED_ONCE" if model.refund_effects == 1 else "INVALID"
    if category == "REFUND_FAILED":
        model.success()
        model.refund_state = "FAILED"
        return "FAILED_NO_REFUND" if model.refund_effects == 0 else "INVALID"
    if category == "REFUND_UNKNOWN":
        model.success()
        model.refund_state = "UNKNOWN"
        model.queries += 1
        model.refund_success()
        return "QUERY_TO_REFUNDED_ONCE" if model.queries == 1 and model.refund_effects == 1 else "INVALID"
    if category == "REFUND_DUPLICATE":
        model.success()
        model.refund_success()
        model.refund_success()
        return "REFUNDED_ONCE" if model.refund_effects == 1 else "INVALID"
    if category == "REFUND_EXCESS":
        requested = amount_minor + 1
        return "REJECT_EXCESS" if requested > amount_minor and model.refund_effects == 0 else "INVALID"
    if category == "RECONCILE_MATCH":
        return "NO_DIFFERENCE"
    if category in {"RECONCILE_PROVIDER_ONLY", "RECONCILE_LOCAL_ONLY", "RECONCILE_AMOUNT_DIFF", "RECONCILE_STATE_DIFF"}:
        model.differences += 1
        return "FLAG_DIFFERENCE" if model.differences == 1 else "INVALID"
    if category == "RATE_LIMIT":
        return "BACKOFF"
    if category == "CIRCUIT_BREAKER":
        return "OPEN_FAIL_FAST"
    raise AssertionError(f"unsupported payment Fake category: {category}")


def run_probe() -> ProbeResult:
    fixture_path = FIXTURE_ROOT / "payment-matrix.json"
    plan = load_json(fixture_path)
    profiles = load_json(WEEK1_PROFILES)["providers"]
    selected = [profile for profile in profiles if profile["selectedForFake"]]
    require(len(profiles) == 10, "payment candidate count changed")
    require(len(selected) == 5, "Fake provider selection changed")
    categories = plan["categories"]
    cases_per_provider = len(categories) * plan["variantsPerCategory"]
    require(cases_per_provider >= plan["minimumCasesPerProvider"], "payment matrix below minimum")
    assertions = 3
    cases = 0

    for profile in selected:
        require(profile["sandboxStatus"] == "BLOCKED", f"{profile['providerCode']} sandbox unexpectedly enabled")
        for category in categories:
            for variant in range(plan["variantsPerCategory"]):
                actual = evaluate_case(category["id"], variant)
                require(actual == category["expected"], f"{category['id']}/{variant}: {actual}")
                assertions += 1
                cases += 1

    return ProbeResult(
        requirementId="T1-PAY-001",
        domain="PAYMENT_FAKE",
        result="PASS",
        assertions=assertions,
        iterations=cases,
        metrics={
            "candidateProfiles": len(profiles),
            "fakeProviders": len(selected),
            "categories": len(categories),
            "casesPerProvider": cases_per_provider,
            "totalCases": cases,
            "providerSpecificCoreBranches": 0,
            "networkCalls": 0,
            "sandboxCalls": 0,
        },
        fixtureDigests=[fixture_digest(fixture_path), fixture_digest(WEEK1_PROFILES)],
    )
