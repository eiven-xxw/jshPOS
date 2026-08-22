package com.jingshanghui.pos.payment.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.payment.domain.TenderRules.AllocationSpec;
import com.jingshanghui.pos.payment.domain.TenderRules.AllocationState;
import com.jingshanghui.pos.payment.domain.TenderStates.AllocationStatus;
import com.jingshanghui.pos.payment.domain.TenderStates.PlanStatus;
import com.jingshanghui.pos.payment.domain.TenderStates.TenderType;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T2-PAY-004 金额守恒、严格顺序、UNKNOWN 和退款原份额固定回归。 */
class TenderRulesTest {

    private static final String A1 = "01K2A000000000000000000071";
    private static final String A2 = "01K2A000000000000000000072";
    private static final String A3 = "01K2A000000000000000000073";

    @Test
    void freezesDeterministicTwoToEightAllocationPlan() {
        var result = TenderRules.validatePlan(1_299, "CNY", List.of(
            spec(A2, 2, TenderType.CASH, 299), spec(A1, 1, TenderType.ELECTRONIC, 1_000)));
        assertThat(result).extracting(AllocationSpec::allocationId).containsExactly(A1, A2);

        assertInvalid(() -> TenderRules.validatePlan(100, "USD", List.of(
            spec(A1, 1, TenderType.ELECTRONIC, 50), spec(A2, 2, TenderType.ELECTRONIC, 50))),
            "TENDER-CURRENCY-001");
        assertInvalid(() -> TenderRules.validatePlan(100, "CNY", null), "TENDER-PLAN-001");
        assertInvalid(() -> TenderRules.validatePlan(100, "CNY", List.of(
            spec(A1, 1, TenderType.ELECTRONIC, 100))), "TENDER-PLAN-001");
        assertInvalid(() -> TenderRules.validatePlan(900, "CNY", List.of(
            spec(A1, 1, TenderType.ELECTRONIC, 100), spec(A2, 2, TenderType.ELECTRONIC, 100),
            spec(A3, 3, TenderType.ELECTRONIC, 100), spec("01K2A000000000000000000074", 4, TenderType.ELECTRONIC, 100),
            spec("01K2A000000000000000000075", 5, TenderType.ELECTRONIC, 100),
            spec("01K2A000000000000000000076", 6, TenderType.ELECTRONIC, 100),
            spec("01K2A000000000000000000077", 7, TenderType.ELECTRONIC, 100),
            spec("01K2A000000000000000000078", 8, TenderType.ELECTRONIC, 100),
            spec("01K2A000000000000000000079", 9, TenderType.ELECTRONIC, 100))), "TENDER-PLAN-001");
    }

    @Test
    void rejectsSequenceCashAndAmountViolations() {
        assertInvalid(() -> TenderRules.validatePlan(100, "CNY", List.of(
            spec(A1, 2, TenderType.ELECTRONIC, 50), spec(A2, 3, TenderType.ELECTRONIC, 50))),
            "TENDER-SEQUENCE-001");
        assertInvalid(() -> TenderRules.validatePlan(100, "CNY", List.of(
            spec(A1, 1, null, 50), spec(A2, 2, TenderType.ELECTRONIC, 50))), "TENDER-SEQUENCE-001");
        assertInvalid(() -> TenderRules.validatePlan(100, "CNY", List.of(
            spec(A1, 1, TenderType.CASH, 50), spec(A2, 2, TenderType.CASH, 50))), "TENDER-CASH-001");
        assertInvalid(() -> TenderRules.validatePlan(100, "CNY", List.of(
            spec(A1, 1, TenderType.CASH, 50), spec(A2, 2, TenderType.ELECTRONIC, 50))), "TENDER-CASH-002");
        assertInvalid(() -> TenderRules.validatePlan(101, "CNY", List.of(
            spec(A1, 1, TenderType.ELECTRONIC, 50), spec(A2, 2, TenderType.CASH, 50))), "TENDER-AMOUNT-002");
        assertInvalid(() -> TenderRules.validatePlan(100, "CNY", List.of(
            spec("bad", 1, TenderType.ELECTRONIC, 50), spec(A2, 2, TenderType.CASH, 50))), "PAY-ID-001");
        assertInvalid(() -> TenderRules.validatePlan(100, "CNY", List.of(
            spec(A1, 1, TenderType.ELECTRONIC, 0), spec(A2, 2, TenderType.CASH, 100))), "PAY-AMOUNT-001");
        assertInvalid(() -> TenderRules.validatePlan(100, "CNY", List.of(
            spec(A1, 1, TenderType.ELECTRONIC, 50), spec(A1, 2, TenderType.CASH, 50))), "TENDER-PLAN-003");
    }

    @Test
    void enforcesStrictCollectionOrderAndUnknownQueryOnly() {
        TenderRules.requirePlanCollectable(PlanStatus.FROZEN);
        TenderRules.requirePlanCollectable(PlanStatus.COLLECTING);
        assertInvalid(() -> TenderRules.requirePlanCollectable(PlanStatus.UNKNOWN), "TENDER-UNKNOWN-001");
        assertInvalid(() -> TenderRules.requirePlanCollectable(PlanStatus.CANCELLED), "TENDER-STATE-004");
        var planned = states(AllocationStatus.PLANNED, AllocationStatus.PLANNED);
        TenderRules.requireCollectable(planned, A1);
        assertInvalid(() -> TenderRules.requireCollectable(planned, A2), "TENDER-SEQUENCE-002");
        assertInvalid(() -> TenderRules.requireCollectable(planned, A3), "TENDER-NOT-VISIBLE");
        assertInvalid(() -> TenderRules.requireCollectable(states(AllocationStatus.UNKNOWN,
            AllocationStatus.PLANNED), A1), "TENDER-UNKNOWN-001");
        assertInvalid(() -> TenderRules.requireCollectable(states(AllocationStatus.PROCESSING,
            AllocationStatus.PLANNED), A1), "TENDER-UNKNOWN-001");
        assertInvalid(() -> TenderRules.requireCollectable(states(AllocationStatus.SUCCEEDED,
            AllocationStatus.PLANNED), A1), "TENDER-STATE-001");
        TenderRules.requireCollectable(states(AllocationStatus.SUCCEEDED, AllocationStatus.PLANNED), A2);
    }

    @Test
    void projectsEveryMeaningfulPlanStateWithoutRegressingUnknown() {
        assertThat(TenderRules.project(states(AllocationStatus.PLANNED, AllocationStatus.PLANNED), 100).status())
            .isEqualTo(PlanStatus.FROZEN);
        assertThat(TenderRules.project(states(AllocationStatus.PROCESSING, AllocationStatus.PLANNED), 100).status())
            .isEqualTo(PlanStatus.COLLECTING);
        assertThat(TenderRules.project(states(AllocationStatus.UNKNOWN, AllocationStatus.PLANNED), 100).status())
            .isEqualTo(PlanStatus.UNKNOWN);
        assertThat(TenderRules.project(states(AllocationStatus.FAILED, AllocationStatus.PLANNED), 100).status())
            .isEqualTo(PlanStatus.FAILED);
        var partial = TenderRules.project(states(AllocationStatus.SUCCEEDED, AllocationStatus.PLANNED), 100);
        assertThat(partial.status()).isEqualTo(PlanStatus.COLLECTING);
        assertThat(partial.succeededAmountMinor()).isEqualTo(50);
        assertThat(partial.occupiedAmountMinor()).isEqualTo(50);
        assertThat(TenderRules.project(states(AllocationStatus.SUCCEEDED, AllocationStatus.SUCCEEDED), 100).status())
            .isEqualTo(PlanStatus.PAID);
    }

    @Test
    void cancellationRejectsSucceededProcessingAndUnknownAllocations() {
        TenderRules.requireCancellable(PlanStatus.FROZEN,
            states(AllocationStatus.PLANNED, AllocationStatus.PLANNED));
        assertInvalid(() -> TenderRules.requireCancellable(PlanStatus.COLLECTING,
            states(AllocationStatus.SUCCEEDED, AllocationStatus.PLANNED)), "TENDER-CANCEL-001");
        assertInvalid(() -> TenderRules.requireCancellable(PlanStatus.UNKNOWN,
            states(AllocationStatus.UNKNOWN, AllocationStatus.PLANNED)), "TENDER-CANCEL-001");
        assertInvalid(() -> TenderRules.requireCancellable(PlanStatus.PAID,
            states(AllocationStatus.SUCCEEDED, AllocationStatus.SUCCEEDED)), "TENDER-CANCEL-001");
    }

    @Test
    void restoresRefundToOriginalSuccessfulTendersAndLastShareAbsorbsRemainder() {
        var three = List.of(
            state(A1, 1, TenderType.ELECTRONIC, AllocationStatus.SUCCEEDED, 333),
            state(A2, 2, TenderType.ELECTRONIC, AllocationStatus.SUCCEEDED, 333),
            state(A3, 3, TenderType.CASH, AllocationStatus.SUCCEEDED, 334));
        var shares = TenderRules.allocateRefund(101, three);
        assertThat(shares).extracting(TenderRules.RefundShare::amountMinor).containsExactly(33L, 33L, 35L);
        assertThat(shares).extracting(TenderRules.RefundShare::tenderType)
            .containsExactly(TenderType.ELECTRONIC, TenderType.ELECTRONIC, TenderType.CASH);
        assertInvalid(() -> TenderRules.allocateRefund(1, states(AllocationStatus.PLANNED,
            AllocationStatus.PLANNED)), "TENDER-REFUND-001");
        assertInvalid(() -> TenderRules.allocateRefund(101, states(AllocationStatus.SUCCEEDED,
            AllocationStatus.SUCCEEDED)), "TENDER-REFUND-001");
    }

    @Test
    void matchesSharedJavaDartGoldenDigest() throws Exception {
        JsonNode vector = new ObjectMapper().readTree(Files.readString(sharedVectorPath()))
            .path("cases").get(0);
        List<AllocationSpec> allocations = new ArrayList<>();
        for (JsonNode allocation : vector.path("allocations")) {
            allocations.add(new AllocationSpec(allocation.path("allocationId").asText(),
                allocation.path("sequenceNo").asInt(),
                TenderType.valueOf(allocation.path("tenderType").asText()),
                allocation.path("amountMinor").asLong()));
        }

        String actual = TenderRules.contentSha256(vector.path("planId").asText(),
            vector.path("orderId").asText(), vector.path("orderSnapshotSha256").asText(),
            vector.path("storeId").asLong(), vector.path("terminalId").asText(),
            vector.path("shiftId").asText(), LocalDate.parse(vector.path("businessDate").asText()),
            vector.path("receivableAmountMinor").asLong(), vector.path("currency").asText(), allocations);

        assertThat(actual).isEqualTo(vector.path("expectedContentSha256").asText());
    }

    private Path sharedVectorPath() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(
                "contracts/t2/gate7b-pay004/tender-golden-vectors-v1.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到 PAY-004 Java/Dart 共用摘要金标");
    }

    private List<AllocationState> states(AllocationStatus first, AllocationStatus second) {
        return List.of(state(A1, 1, TenderType.ELECTRONIC, first, 50),
            state(A2, 2, TenderType.CASH, second, 50));
    }

    private AllocationSpec spec(String id, int sequence, TenderType type, long amount) {
        return new AllocationSpec(id, sequence, type, amount);
    }

    private AllocationState state(String id, int sequence, TenderType type, AllocationStatus status, long amount) {
        return new AllocationState(id, sequence, type, status, amount);
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(ServiceException.class).hasMessageContaining(code);
    }
}
