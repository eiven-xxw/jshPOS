package com.jingshanghui.pos.inventory.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType.SALE_OUT;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType.SALE_RETURN_IN;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType.STOCKTAKE_GAIN;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType.STOCKTAKE_LOSS;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType.PURCHASE_RECEIPT_IN;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType.PURCHASE_RETURN_OUT;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType.TRANSFER_IN;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType.TRANSFER_OUT;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType.OPENING_IN;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType.OPENING_ADJUSTMENT;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.NegativeStockMode.ALLOW_AND_ALERT;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.NegativeStockMode.ALLOW_WITH_PERMISSION;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.NegativeStockMode.DENY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 覆盖库存数量、方向、来源状态和负库存的 P0 领域不变量。 */
class InventoryRulesTest {

    @Test
    void normalizesPositiveQuantitiesAndMovementDirection() {
        assertThat(InventoryRules.positive(new BigDecimal("2.5"), "quantity"))
            .isEqualByComparingTo("2.500000");
        assertThat(InventoryRules.signedDelta(SALE_OUT, new BigDecimal("2.5")))
            .isEqualByComparingTo("-2.500000");
        assertThat(InventoryRules.signedDelta(SALE_RETURN_IN, new BigDecimal("2.5")))
            .isEqualByComparingTo("2.500000");
        assertThat(InventoryRules.signedDelta(STOCKTAKE_GAIN, new BigDecimal("2.5")))
            .isEqualByComparingTo("2.500000");
        assertThat(InventoryRules.signedDelta(STOCKTAKE_LOSS, new BigDecimal("2.5")))
            .isEqualByComparingTo("-2.500000");
        assertThat(InventoryRules.signedDelta(PURCHASE_RECEIPT_IN, new BigDecimal("2.5")))
            .isEqualByComparingTo("2.500000");
        assertThat(InventoryRules.signedDelta(PURCHASE_RETURN_OUT, new BigDecimal("2.5")))
            .isEqualByComparingTo("-2.500000");
        assertThat(InventoryRules.signedDelta(TRANSFER_OUT, new BigDecimal("2.5")))
            .isEqualByComparingTo("-2.500000");
        assertThat(InventoryRules.signedDelta(TRANSFER_IN, new BigDecimal("2.5")))
            .isEqualByComparingTo("2.500000");
        assertThat(InventoryRules.signedDelta(OPENING_IN, new BigDecimal("2.5")))
            .isEqualByComparingTo("2.500000");
        assertThat(InventoryRules.signedDelta(OPENING_ADJUSTMENT, new BigDecimal("2.5")))
            .isEqualByComparingTo("2.500000");
    }

    @Test
    void rejectsZeroNegativeOverScaleAndOversizedQuantities() {
        assertThatThrownBy(() -> InventoryRules.positive(BigDecimal.ZERO, "quantity"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.positive(new BigDecimal("-1"), "quantity"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.positive(new BigDecimal("1.0000001"), "quantity"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.positive(new BigDecimal("10000000000000"), "quantity"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.positive(null, "quantity"))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void computesAvailableUsingAllSeparatedQuantityBuckets() {
        assertThat(InventoryRules.available(new BigDecimal("10"), new BigDecimal("2"),
            new BigDecimal("1"), new BigDecimal("3"))).isEqualByComparingTo("4.000000");
        assertThat(InventoryRules.available(null, null, null, null)).isEqualByComparingTo("0.000000");
    }

    @Test
    void enforcesNegativeStockModes() {
        assertThat(InventoryRules.requiresNegativeAlert(DENY, new BigDecimal("0.000000"))).isFalse();
        assertThat(InventoryRules.requiresNegativeAlert(ALLOW_AND_ALERT, new BigDecimal("-0.1"))).isTrue();
        assertThatThrownBy(() -> InventoryRules.requiresNegativeAlert(DENY, new BigDecimal("-0.1")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("INV-STOCK-001");
        assertThatThrownBy(() -> InventoryRules.requiresNegativeAlert(ALLOW_WITH_PERMISSION,
            new BigDecimal("-0.1"))).isInstanceOf(ServiceException.class).hasMessageContaining("INV-STOCK-002");
    }

    @Test
    void acceptsOnlyCompletedPaidOrderAndSucceededRefund() {
        InventoryRules.requireSourceState("ORDER", "COMPLETED", "PAID");
        InventoryRules.requireSourceState("REFUND", "SUCCEEDED", null);
        assertThatThrownBy(() -> InventoryRules.requireSourceState("ORDER", "CONFIRMED", "PAID"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.requireSourceState("ORDER", "COMPLETED", "UNPAID"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.requireSourceState("REFUND", "UNKNOWN", null))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void validatesCanonicalUlid() {
        InventoryRules.requireUlid("01K2A000000000000000000001", "id");
        assertThatThrownBy(() -> InventoryRules.requireUlid("bad", "id"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.requireUlid(null, "id"))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void permitsOnlyOwnerSourceMovementPairs() {
        InventoryRules.requireOwnedMovement("STOCKTAKE", STOCKTAKE_GAIN);
        InventoryRules.requireOwnedMovement("STOCKTAKE", STOCKTAKE_LOSS);
        InventoryRules.requireOwnedMovement("PURCHASE_RECEIPT", PURCHASE_RECEIPT_IN);
        InventoryRules.requireOwnedMovement("PURCHASE_RETURN", PURCHASE_RETURN_OUT);
        InventoryRules.requireOwnedMovement("TRANSFER_DISPATCH", TRANSFER_OUT);
        InventoryRules.requireOwnedMovement("TRANSFER_RECEIPT", TRANSFER_IN);
        InventoryRules.requireOwnedMovement("BUSINESS_MIGRATION", OPENING_IN);
        InventoryRules.requireOwnedMovement("BUSINESS_MIGRATION_ADJUSTMENT", OPENING_ADJUSTMENT);
        assertThatThrownBy(() -> InventoryRules.requireOwnedMovement("STOCKTAKE", SALE_OUT))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.requireOwnedMovement("PURCHASE_RECEIPT", PURCHASE_RETURN_OUT))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.requireOwnedMovement("UNKNOWN", STOCKTAKE_GAIN))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.requireOwnedMovement("TRANSFER_DISPATCH", TRANSFER_IN))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.requireOwnedMovement("BUSINESS_MIGRATION", SALE_OUT))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> InventoryRules.requireOwnedMovement("BUSINESS_MIGRATION_ADJUSTMENT", OPENING_IN))
            .isInstanceOf(ServiceException.class);
    }
}
