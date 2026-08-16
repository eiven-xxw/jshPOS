package com.jingshanghui.pos.order.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderRulesTest {

    private static final String LINE = "01K2A000000000000000000041";

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
    void permitsOnlyTheFrozenOrderStatePath() {
        OrderRules.requireTransition("DRAFT", "PENDING_PAYMENT");
        OrderRules.requireTransition("PENDING_PAYMENT", "CONFIRMED");
        OrderRules.requireTransition("CONFIRMED", "COMPLETED");
        OrderRules.requireTransition("DRAFT", "CANCELLED");
        assertThatThrownBy(() -> OrderRules.requireTransition("COMPLETED", "DRAFT"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_STATE_CONFLICT");
    }
}
