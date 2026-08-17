package com.jingshanghui.pos.order.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRulesTest {

    private static final String LINE = "01K2A000000000000000000041";
    private static final String LINE_2 = "01K2A000000000000000000042";

    @Test
    void validatesUlidsIdempotencyAndExactQuantities() {
        assertThat(OrderRules.requireUlid(LINE, "lineId")).isEqualTo(LINE);
        assertThat(OrderRules.requireIdempotencyKey("cash-order-key-0001")).isEqualTo("cash-order-key-0001");
        assertThat(OrderRules.requireQuantity("1.000000")).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(OrderRules.requireQuantity("0.125")).isEqualByComparingTo("0.125");
        assertThatThrownBy(() -> OrderRules.requireUlid("bad", "lineId")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.requireUlid(null, "lineId")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.requireIdempotencyKey("short")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.requireIdempotencyKey(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.requireQuantity(null)).isInstanceOf(ServiceException.class);
        for (String invalid : List.of("0", "-1", "0.0000001", "1e2", "NaN", "12345678901234")) {
            assertThatThrownBy(() -> OrderRules.requireQuantity(invalid)).isInstanceOf(ServiceException.class);
        }
    }

    @Test
    void roundsHalfUpAndConservesOrderMoney() {
        assertThat(OrderRules.lineGross(100, new BigDecimal("0.125"))).isEqualTo(13);
        assertThat(OrderRules.lineGross(101, new BigDecimal("1.5"))).isEqualTo(152);
        var line = new OrderRules.LineAmount(LINE, 1, 701L, "1", 1299, 1299, 1299, "TENANT_BASE");
        assertThat(OrderRules.validateOrder(List.of(line), 1299, 1299)).isEqualTo(1299);
    }

    @Test
    void rejectsLineIdentityPriceAndAmountTampering() {
        var valid = new OrderRules.LineAmount(LINE, 1, 701L, "1", 1299, 1299, 1299, "TENANT_BASE");
        assertThatThrownBy(() -> OrderRules.validateOrder(null, 0, 0)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.validateOrder(List.of(), 0, 0)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.validateOrder(java.util.Collections.nCopies(501, valid), 0, 0))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.validateOrder(List.of(valid, valid), 2598, 2598)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.validateOrder(List.of(
            new OrderRules.LineAmount(LINE, 1, 0L, "1", 1, 1, 1, "TENANT_BASE")), 1, 1))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.validateOrder(List.of(
            new OrderRules.LineAmount(LINE, 1, null, "1", 1, 1, 1, "TENANT_BASE")), 1, 1))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.validateOrder(List.of(
            new OrderRules.LineAmount(LINE, 0, 701L, "1", 1, 1, 1, "TENANT_BASE")), 1, 1))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.validateOrder(List.of(
            new OrderRules.LineAmount(LINE, 1, 701L, "1", 1, 1, 1, "CLIENT_OVERRIDE")), 1, 1))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.validateOrder(List.of(
            new OrderRules.LineAmount(LINE, 1, 701L, "2", 10, 19, 19, "STORE_OVERRIDE")), 19, 19))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_AMOUNT_CHANGED");
        assertThatThrownBy(() -> OrderRules.validateOrder(List.of(
            new OrderRules.LineAmount(LINE, 1, 701L, "1", 10, 10, 9, "STORE_OVERRIDE")), 10, 10))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_AMOUNT_CHANGED");
        assertThatThrownBy(() -> OrderRules.validateOrder(List.of(valid), 1298, 1299))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_AMOUNT_CHANGED");
        assertThatThrownBy(() -> OrderRules.validateOrder(List.of(valid), 1299, 1298))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_AMOUNT_CHANGED");
    }

    @Test
    void validatesCashAndSafeIntegerRange() {
        assertThat(OrderRules.cash(1299, 2000)).isEqualTo(new OrderRules.CashAmounts(2000, 701, 1299));
        assertThatThrownBy(() -> OrderRules.cash(1299, 1298)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("CASH_TENDER_INSUFFICIENT");
        assertThatThrownBy(() -> OrderRules.requireMoney(-1, "amount")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OrderRules.requireMoney(9_007_199_254_740_992L, "amount"))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void validatesPromotedHeaderLinesAndSourceAllocation() {
        var line = promotedLine(LINE, 1, 701L, 1000, 100, 0, 900,
            "TENANT_BASE", Map.of("RULE:RULE-001", 100L));
        assertThat(OrderRules.validatePromotedOrder(List.of(line), 1000, 100, 0, 900))
            .isEqualTo(new OrderRules.PromotedTotals(1000, 100, 0, 900));
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(
            new OrderRules.PromotedLineAmount(LINE, 1, 701L, "2", 500,
                1000, 100, 0, 901, "TENANT_BASE", Map.of("RULE:RULE-001", 100L))),
            1000, 100, 0, 901)).hasMessageContaining("ORDER_AMOUNT_CHANGED");
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(
            new OrderRules.PromotedLineAmount(LINE, 1, 701L, "2", 500,
                1000, 100, 0, 900, "TENANT_BASE", Map.of("RULE:RULE-001", 99L))),
            1000, 100, 0, 900)).hasMessageContaining("source allocation");
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(line), 1000, 99, 0, 901))
            .hasMessageContaining("ORDER_AMOUNT_CHANGED");
    }

    @Test
    void rejectsPromotedAggregateBoundsAndLineIdentityTampering() {
        var valid = promotedLine(LINE, 1, 701L, 1000, 100, 0, 900,
            "TENANT_BASE", Map.of("RULE:RULE-001", 100L));
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(null, 0, 0, 0, 0))
            .hasMessageContaining("ORD-LINE-001");
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(), 0, 0, 0, 0))
            .hasMessageContaining("ORD-LINE-001");
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(
            java.util.Collections.nCopies(501, valid), 0, 0, 0, 0)).hasMessageContaining("ORD-LINE-001");
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(valid,
            promotedLine(LINE, 2, 702L, 1000, 100, 0, 900,
                "TENANT_BASE", Map.of("RULE:RULE-002", 100L))), 2000, 200, 0, 1800))
            .hasMessageContaining("ORD-LINE-003");
        for (Long skuId : java.util.Arrays.asList(null, 0L)) {
            assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(
                promotedLine(LINE, 1, skuId, 1000, 100, 0, 900,
                    "TENANT_BASE", Map.of("RULE:RULE-001", 100L))), 1000, 100, 0, 900))
                .hasMessageContaining("ORD-LINE-003");
        }
        for (int lineNo : List.of(0, 501)) {
            assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(
                promotedLine(LINE, lineNo, 701L, 1000, 100, 0, 900,
                    "TENANT_BASE", Map.of("RULE:RULE-001", 100L))), 1000, 100, 0, 900))
                .hasMessageContaining("ORD-LINE-002");
        }
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(valid,
            promotedLine(LINE_2, 1, 702L, 1000, 100, 0, 900,
                "TENANT_BASE", Map.of("RULE:RULE-002", 100L))), 2000, 200, 0, 1800))
            .hasMessageContaining("ORD-LINE-002");
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(
            promotedLine(LINE, 1, 701L, 1000, 100, 0, 900,
                "CLIENT_OVERRIDE", Map.of("RULE:RULE-001", 100L))), 1000, 100, 0, 900))
            .hasMessageContaining("ORD-PRICE-001");
    }

    @Test
    void rejectsPromotedLineMoneyAndAllocationTampering() {
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(
            promotedLine(LINE, 1, 701L, 999, 100, 0, 900,
                "TENANT_BASE", Map.of("RULE:RULE-001", 100L))), 999, 100, 0, 900))
            .hasMessageContaining("promoted line amount");
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(
            promotedLine(LINE, 1, 701L, 1000, 1100, 0, 0,
                "TENANT_BASE", Map.of("RULE:RULE-001", 1100L))), 1000, 1100, 0, 0))
            .hasMessageContaining("promoted line amount");
        assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(
            promotedLine(LINE, 1, 701L, 1000, 100, 0, 900,
                "TENANT_BASE", null)), 1000, 100, 0, 900))
            .hasMessageContaining("source allocation is required");

        Map<String, Long> nullKey = new HashMap<>();
        nullKey.put(null, 100L);
        Map<String, Long> nullValue = new HashMap<>();
        nullValue.put("RULE:RULE-001", null);
        for (Map<String, Long> invalid : List.of(
            nullKey, Map.of("bad", 100L), nullValue, Map.of("RULE:RULE-001", 0L))) {
            assertThatThrownBy(() -> OrderRules.validatePromotedOrder(List.of(
                promotedLine(LINE, 1, 701L, 1000, 100, 0, 900,
                    "TENANT_BASE", invalid)), 1000, 100, 0, 900))
                .hasMessageContaining("invalid promotion source allocation");
        }
    }

    @Test
    void rejectsEveryPromotedHeaderMismatchIndependently() {
        var line = promotedLine(LINE, 1, 701L, 1000, 100, 0, 900,
            "TENANT_BASE", Map.of("RULE:RULE-001", 100L));
        for (long[] header : List.of(
            new long[]{999, 100, 0, 900},
            new long[]{1000, 99, 0, 900},
            new long[]{1000, 100, 1, 900},
            new long[]{1000, 100, 0, 899})) {
            assertThatThrownBy(() -> OrderRules.validatePromotedOrder(
                List.of(line), header[0], header[1], header[2], header[3]))
                .hasMessageContaining("promoted order header");
        }
    }

    @Test
    void permitsOnlyTheFrozenOrderStatePath() {
        OrderRules.requireTransition("DRAFT", "PENDING_PAYMENT");
        OrderRules.requireTransition("PENDING_PAYMENT", "CONFIRMED");
        OrderRules.requireTransition("CONFIRMED", "COMPLETED");
        OrderRules.requireTransition("DRAFT", "CANCELLED");
        assertThatThrownBy(() -> OrderRules.requireTransition("COMPLETED", "DRAFT"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_STATE_CONFLICT");
    }

    private static OrderRules.PromotedLineAmount promotedLine(String lineId, int lineNo, Long skuId,
                                                               long gross, long discount, long surcharge,
                                                               long payable, String priceSource,
                                                               Map<String, Long> sourceAllocations) {
        return new OrderRules.PromotedLineAmount(lineId, lineNo, skuId, "2", 500,
            gross, discount, surcharge, payable, priceSource, sourceAllocations);
    }
}
