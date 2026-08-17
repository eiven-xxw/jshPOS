package com.jingshanghui.pos.promotion.domain;

import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine.*;
import com.jingshanghui.pos.promotion.domain.PromotionModels.QuoteLine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.QuoteResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PRM-002 金额、阈值、职责分离前置判定和稳定分摊测试。 */
class ManualAdjustmentEngineTest {
    private static final String AUTH = "01K5R000000000000000000050";
    private final ManualAdjustmentEngine engine = new ManualAdjustmentEngine();
    private final Policy policy = new Policy(31, "a".repeat(64), 100, 1000, 20, 9, List.of(1L, 10L));
    private final List<LineContext> contexts = List.of(
        new LineContext("01K5R000000000000000000001", 1, 101L, new BigDecimal("2.000000")),
        new LineContext("01K5R000000000000000000002", 2, 102L, new BigDecimal("1.000000")));
    private final QuoteResult quote = new QuoteResult(1000, 100, 900, List.of(
        new QuoteLine(contexts.get(0).lineId(), 600, 60, 540),
        new QuoteLine(contexts.get(1).lineId(), 400, 40, 360)), List.of(), List.of(), List.of());

    @Test
    void lineFixedPriceUsesQuantityAndRequiresApprovalAboveThreshold() {
        Preview value = engine.preview(quote, contexts, new Command(AUTH, ActionType.LINE_FIXED_PRICE,
            contexts.get(0).lineId(), "200", PaymentMethod.NON_CASH), policy);
        assertThat(value.incrementalDiscountMinor()).isEqualTo(140);
        assertThat(value.requiresApproval()).isTrue();
        assertThat(value.result().lineDiscounts()).containsEntry(contexts.get(0).lineId(), 200L);
        assertThat(value.result().payableAmountMinor()).isEqualTo(760);
    }

    @Test
    void orderAmountAndPercentUseStableConservingAllocation() {
        Preview amount = engine.preview(quote, contexts, new Command(AUTH, ActionType.ORDER_AMOUNT_OFF,
            null, "90", PaymentMethod.NON_CASH), policy);
        assertThat(amount.result().lineDiscounts()).containsExactly(
            org.assertj.core.api.Assertions.entry(contexts.get(0).lineId(), 114L),
            org.assertj.core.api.Assertions.entry(contexts.get(1).lineId(), 76L));
        assertThat(amount.requiresApproval()).isFalse();
        Preview percent = engine.preview(quote, contexts, new Command(AUTH, ActionType.ORDER_PERCENT_OFF,
            null, "0.12500000", PaymentMethod.NON_CASH), policy);
        assertThat(percent.incrementalDiscountMinor()).isEqualTo(113);
        assertThat(percent.result().discountAmountMinor()).isEqualTo(213);
    }

    @Test
    void roundingRequiresCashWhitelistAndAbsoluteLimit() {
        QuoteResult odd = new QuoteResult(1003, 100, 903, List.of(
            new QuoteLine(contexts.get(0).lineId(), 603, 63, 540),
            new QuoteLine(contexts.get(1).lineId(), 400, 37, 363)), List.of(), List.of(), List.of());
        Preview result = engine.preview(odd, contexts, new Command(AUTH, ActionType.ROUNDING,
            null, "10", PaymentMethod.CASH), policy);
        assertThat(result.incrementalDiscountMinor()).isEqualTo(3);
        assertThat(result.result().payableAmountMinor()).isEqualTo(900);
        assertThatThrownBy(() -> engine.preview(odd, contexts, new Command(AUTH, ActionType.ROUNDING,
            null, "10", PaymentMethod.NON_CASH), policy)).hasMessageContaining("PRM-AMOUNT-015");
        assertThatThrownBy(() -> engine.preview(odd, contexts, new Command(AUTH, ActionType.ROUNDING,
            null, "100", PaymentMethod.CASH), policy)).hasMessageContaining("PRM-AMOUNT-016");
    }

    @Test
    void invalidInputsAndPolicyBoundsFailClosed() {
        assertThatThrownBy(() -> engine.preview(quote, contexts, new Command(AUTH, ActionType.LINE_FIXED_PRICE,
            contexts.get(0).lineId(), "400", PaymentMethod.NON_CASH), policy)).hasMessageContaining("PRM-AMOUNT-013");
        assertThatThrownBy(() -> engine.preview(quote, contexts, new Command(AUTH, ActionType.ORDER_AMOUNT_OFF,
            null, "901", PaymentMethod.NON_CASH), policy)).hasMessageContaining("PRM-AMOUNT-014");
        assertThatThrownBy(() -> engine.preview(quote, contexts, new Command(AUTH, ActionType.ORDER_PERCENT_OFF,
            null, "1.000000001", PaymentMethod.NON_CASH), policy)).hasMessageContaining("PRM-AMOUNT-019");
        assertThatThrownBy(() -> new Policy(0, "bad", 0, 0, 0, 0, List.of()))
            .hasMessageContaining("PRM-AUTH-010");
    }

    @Test
    void policyValidationRejectsEveryInvalidBoundary() {
        assertPolicyInvalid(new PolicyInput(0, "a".repeat(64), 0, 0, 0, 0, List.of(1L)));
        assertPolicyInvalid(new PolicyInput(1, null, 0, 0, 0, 0, List.of(1L)));
        assertPolicyInvalid(new PolicyInput(1, "A".repeat(64), 0, 0, 0, 0, List.of(1L)));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), -1, 0, 0, 0, List.of(1L)));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), 2, 1, 0, 0, List.of(1L)));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), 0, PromotionModels.MAX_SAFE_MONEY_MINOR + 1,
            0, 0, List.of(1L)));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), 0, 1, -1, 0, List.of(1L)));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), 0, 1, 0, -1, List.of(1L)));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), 0, 1, 0, 2, List.of(1L)));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), 0, 1, 0, 0, null));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), 0, 1, 0, 0, List.of()));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), 0, 1, 0, 0, Arrays.asList((Long) null)));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), 0, 1, 0, 0, List.of(0L)));
        assertPolicyInvalid(new PolicyInput(1, "a".repeat(64), 0, 1, 0, 0, List.of(10_001L)));
    }

    @Test
    void lineAndCommandRecordsRejectMalformedInputs() {
        assertLineInvalid(null, 1, 1L, new BigDecimal("1"));
        assertLineInvalid("bad", 1, 1L, new BigDecimal("1"));
        assertLineInvalid(contexts.get(0).lineId(), 0, 1L, new BigDecimal("1"));
        assertLineInvalid(contexts.get(0).lineId(), 1, null, new BigDecimal("1"));
        assertLineInvalid(contexts.get(0).lineId(), 1, 0L, new BigDecimal("1"));
        assertLineInvalid(contexts.get(0).lineId(), 1, 1L, null);
        assertLineInvalid(contexts.get(0).lineId(), 1, 1L, BigDecimal.ZERO);
        assertLineInvalid(contexts.get(0).lineId(), 1, 1L, new BigDecimal("1.0000001"));
        assertLineInvalid(contexts.get(0).lineId(), 1, 1L, new BigDecimal("123456789012345.000000"));

        assertCommandInvalid(null, ActionType.ORDER_AMOUNT_OFF, "1", PaymentMethod.CASH);
        assertCommandInvalid("bad", ActionType.ORDER_AMOUNT_OFF, "1", PaymentMethod.CASH);
        assertCommandInvalid(AUTH, null, "1", PaymentMethod.CASH);
        assertCommandInvalid(AUTH, ActionType.ORDER_AMOUNT_OFF, null, PaymentMethod.CASH);
        assertCommandInvalid(AUTH, ActionType.ORDER_AMOUNT_OFF, " ", PaymentMethod.CASH);
        assertCommandInvalid(AUTH, ActionType.ORDER_AMOUNT_OFF, "1".repeat(33), PaymentMethod.CASH);
        assertCommandInvalid(AUTH, ActionType.ORDER_AMOUNT_OFF, "1", null);
    }

    @Test
    void malformedQuoteAndActionBoundariesFailClosed() {
        assertThatThrownBy(() -> engine.preview(null, contexts, command(ActionType.ORDER_AMOUNT_OFF, null, "1"), policy))
            .hasMessageContaining("PRM-AMOUNT-018");
        assertThatThrownBy(() -> engine.preview(quote, null, command(ActionType.ORDER_AMOUNT_OFF, null, "1"), policy))
            .hasMessageContaining("PRM-AMOUNT-018");
        assertThatThrownBy(() -> engine.preview(quote, contexts.subList(0, 1),
            command(ActionType.ORDER_AMOUNT_OFF, null, "1"), policy)).hasMessageContaining("PRM-AMOUNT-018");
        QuoteResult brokenTotal = new QuoteResult(1001, 100, 900, quote.lines(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> engine.preview(brokenTotal, contexts,
            command(ActionType.ORDER_AMOUNT_OFF, null, "1"), policy)).hasMessageContaining("PRM-AMOUNT-018");
        List<LineContext> duplicate = List.of(contexts.get(0),
            new LineContext(contexts.get(0).lineId(), 2, 102L, BigDecimal.ONE));
        assertThatThrownBy(() -> engine.preview(quote, duplicate,
            command(ActionType.ORDER_AMOUNT_OFF, null, "1"), policy)).hasMessageContaining("PRM-AMOUNT-018");
        List<LineContext> wrongLine = List.of(
            new LineContext("01K5R000000000000000000009", 1, 101L, BigDecimal.ONE), contexts.get(1));
        assertThatThrownBy(() -> engine.preview(quote, wrongLine,
            command(ActionType.ORDER_AMOUNT_OFF, null, "1"), policy)).hasMessageContaining("PRM-AMOUNT-018");

        assertThatThrownBy(() -> engine.preview(quote, contexts,
            command(ActionType.LINE_FIXED_PRICE, null, "200"), policy)).hasMessageContaining("PRM-AMOUNT-011");
        assertThatThrownBy(() -> engine.preview(quote, contexts,
            command(ActionType.LINE_FIXED_PRICE, "01K5R000000000000000000009", "200"), policy))
            .hasMessageContaining("PRM-AMOUNT-012");
        assertThatThrownBy(() -> engine.preview(quote, contexts,
            command(ActionType.ORDER_AMOUNT_OFF, null, "0"), policy)).hasMessageContaining("PRM-AMOUNT-014");
        assertThatThrownBy(() -> engine.preview(quote, contexts,
            command(ActionType.ORDER_AMOUNT_OFF, null, "not-a-number"), policy)).hasMessageContaining("PRM-AMOUNT-020");
        assertThatThrownBy(() -> engine.preview(quote, contexts,
            command(ActionType.ORDER_PERCENT_OFF, null, "0"), policy)).hasMessageContaining("PRM-AMOUNT-019");
        assertThatThrownBy(() -> engine.preview(quote, contexts,
            command(ActionType.ORDER_PERCENT_OFF, null, "0.000000001"), policy)).hasMessageContaining("PRM-AMOUNT-019");
    }

    @Test
    void roundingAndHardApprovalCapsFailClosed() {
        assertThatThrownBy(() -> engine.preview(quote, contexts,
            command(ActionType.ROUNDING, contexts.get(0).lineId(), "10", PaymentMethod.CASH), policy))
            .hasMessageContaining("PRM-AMOUNT-015");
        QuoteResult exactMultiple = new QuoteResult(1000, 100, 900, quote.lines(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> engine.preview(exactMultiple, contexts,
            command(ActionType.ROUNDING, null, "10", PaymentMethod.CASH), policy))
            .hasMessageContaining("PRM-AMOUNT-017");
        Policy strict = new Policy(32, "b".repeat(64), 1, 2, 0, 1, List.of(10L));
        assertThatThrownBy(() -> engine.preview(quote, contexts,
            command(ActionType.ORDER_AMOUNT_OFF, null, "3"), strict)).hasMessageContaining("PRM-AUTH-012");
        assertThatThrownBy(() -> engine.preview(quote, contexts,
            command(ActionType.ORDER_AMOUNT_OFF, null, Long.toString(PromotionModels.MAX_SAFE_MONEY_MINOR + 1)), policy))
            .hasMessageContaining("PRM-AMOUNT-020");
    }

    private Command command(ActionType type, String lineId, String amount) {
        return command(type, lineId, amount, PaymentMethod.NON_CASH);
    }

    private Command command(ActionType type, String lineId, String amount, PaymentMethod paymentMethod) {
        return new Command(AUTH, type, lineId, amount, paymentMethod);
    }

    private void assertPolicyInvalid(PolicyInput input) {
        assertThatThrownBy(() -> new Policy(input.version(), input.sha(), input.withoutApproval(), input.withApproval(),
            input.minimumLinePayable(), input.maximumRounding(), input.roundingMultiples()))
            .hasMessageContaining("PRM-AUTH-010");
    }

    private void assertLineInvalid(String lineId, int lineNo, Long skuId, BigDecimal quantity) {
        assertThatThrownBy(() -> new LineContext(lineId, lineNo, skuId, quantity))
            .hasMessageContaining("PRM-AMOUNT-010");
    }

    private void assertCommandInvalid(String authorizationId, ActionType type, String amount,
                                      PaymentMethod paymentMethod) {
        assertThatThrownBy(() -> new Command(authorizationId, type, null, amount, paymentMethod))
            .hasMessageContaining("PRM-AUTH-011");
    }

    private record PolicyInput(long version, String sha, long withoutApproval, long withApproval,
                               long minimumLinePayable, long maximumRounding, List<Long> roundingMultiples) { }
}
