package com.jingshanghui.pos.inventory.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LotInventoryRulesTest {
    private static final LocalDate DAY = LocalDate.of(2026, 8, 23);

    @Test
    void allocatesByExpiryThenReceiptAndLotId() {
        List<LotInventoryRules.Allocation> result = LotInventoryRules.allocateFefo(List.of(
            candidate("01KLOT00000000000000000003", DAY.minusDays(3), DAY.plusDays(5), "4.000000"),
            candidate("01KLOT00000000000000000002", DAY.minusDays(1), DAY.plusDays(1), "3.000000"),
            candidate("01KLOT00000000000000000001", DAY.minusDays(2), DAY.plusDays(1), "2.000000")
        ), new BigDecimal("6.000000"), DAY);

        assertThat(result).extracting(LotInventoryRules.Allocation::lotId).containsExactly(
            "01KLOT00000000000000000001", "01KLOT00000000000000000002", "01KLOT00000000000000000003");
        assertThat(result).extracting(LotInventoryRules.Allocation::quantity).containsExactly(
            new BigDecimal("2.000000"), new BigDecimal("3.000000"), new BigDecimal("1.000000"));
    }

    @Test
    void expiryDayIsSaleableButNextDayFailsClosed() {
        assertThat(LotInventoryRules.allocateFefo(List.of(candidate("01KLOT00000000000000000001",
            DAY.minusDays(2), DAY, "1.000000")), BigDecimal.ONE, DAY)).hasSize(1);
        assertThatThrownBy(() -> LotInventoryRules.requireSaleable(DAY, DAY.plusDays(1)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("LOT-EXPIRED-001");
    }

    @Test
    void insufficientAndOverPrecisionQuantitiesFailClosed() {
        assertThatThrownBy(() -> LotInventoryRules.allocateFefo(List.of(candidate(
            "01KLOT00000000000000000001", DAY, DAY.plusDays(1), "1.000000")),
            new BigDecimal("1.000001"), DAY)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("LOT-BALANCE-001");
        assertThatThrownBy(() -> LotInventoryRules.exactQuantity(new BigDecimal("1.0000001"), "quantity"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("LOT-QUANTITY-002");
    }

    @Test
    void rejectsNullOrNonPositiveInputsAndInvalidDates() {
        assertThatThrownBy(() -> LotInventoryRules.exactQuantity(null, "quantity"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("LOT-QUANTITY-001");
        assertThatThrownBy(() -> LotInventoryRules.exactQuantity(BigDecimal.ZERO, "quantity"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("LOT-QUANTITY-001");
        assertThatThrownBy(() -> LotInventoryRules.requireSaleable(null, DAY))
            .isInstanceOf(ServiceException.class).hasMessageContaining("LOT-DATE-001");
    }

    @Test
    void ignoresZeroCandidatesAndRejectsMissingCandidates() {
        assertThatThrownBy(() -> LotInventoryRules.allocateFefo(List.of(candidate(
            "01KLOT00000000000000000001", DAY, DAY.plusDays(1), "0.000000")), BigDecimal.ONE, DAY))
            .isInstanceOf(ServiceException.class).hasMessageContaining("LOT-BALANCE-001");
        assertThatThrownBy(() -> LotInventoryRules.allocateFefo(null, BigDecimal.ONE, DAY))
            .isInstanceOf(ServiceException.class).hasMessageContaining("LOT-BALANCE-001");
    }

    private static LotInventoryRules.Candidate candidate(String id, LocalDate received, LocalDate expiry,
                                                          String quantity) {
        return new LotInventoryRules.Candidate(id, received, expiry, new BigDecimal(quantity),
            "01KPOLICY000000000000000001");
    }
}
