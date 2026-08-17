package com.jingshanghui.pos.costing.domain;

import com.jingshanghui.pos.costing.domain.CostingRules.BalanceSnapshot;
import com.jingshanghui.pos.costing.domain.CostingRules.CostTransition;
import com.jingshanghui.pos.costing.domain.CostingRules.ValuationInput;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定向量覆盖移动加权、负库存、退货、冲正、零库存和精度边界。 */
class CostingRulesTest {

    @Test
    void valuesFirstAndContinuousPurchaseReceiptsWithHalfEvenPrecision() {
        CostTransition first = calculate("PURCHASE_RECEIPT_IN", "0", "0", "0", "0", "10", "100", false);
        assertTransition(first, "10.000000", "1000.000000", "100.000000", "PURCHASE_FROZEN_PRICE", false);

        CostTransition second = calculate("PURCHASE_RECEIPT_IN", "10", "1000", "100", "100", "10", "200", false);
        assertTransition(second, "20.000000", "3000.000000", "150.000000", "PURCHASE_FROZEN_PRICE", false);
        assertThat(CostingRules.purchaseBaseUnitCost(101, 3, 2)).isEqualByComparingTo("67.333333");
        CostTransition gift = calculate("PURCHASE_RECEIPT_IN", "0", "0", "0", "0", "2", "0", false);
        assertTransition(gift, "2.000000", "0.000000", "0.000000", "PURCHASE_FROZEN_PRICE", false);
        CostTransition giftSale = calculate("SALE_OUT", "2", "0", "0", "0", "-1", null, false);
        assertTransition(giftSale, "1.000000", "0.000000", "0.000000", "MOVING_AVERAGE", false);
    }

    @Test
    void settlesNegativeStockWithoutRewritingPriorEstimatedCost() {
        CostTransition remainsNegative = calculate("PURCHASE_RECEIPT_IN", "-5", "-500", "100", "100",
            "3", "120", false);
        assertTransition(remainsNegative, "-2.000000", "-200.000000", "100.000000",
            "NEGATIVE_SETTLEMENT", false);
        assertThat(remainsNegative.varianceAmountMinor()).isEqualByComparingTo("60.000000");

        CostTransition crossesZero = calculate("PURCHASE_RECEIPT_IN", "-5", "-500", "100", "100",
            "7", "120", false);
        assertTransition(crossesZero, "2.000000", "240.000000", "120.000000",
            "NEGATIVE_SETTLEMENT", false);
        assertThat(crossesZero.varianceAmountMinor()).isEqualByComparingTo("100.000000");

        CostTransition closes = calculate("PURCHASE_RECEIPT_IN", "-5", "-500", "100", "100",
            "5", "120", false);
        assertTransition(closes, "0.000000", "0.000000", "120.000000", "NEGATIVE_SETTLEMENT", false);
    }

    @Test
    void usesOriginalSaleCostOrExplicitEstimatedFallbackForReturns() {
        CostTransition original = calculate("SALE_RETURN_IN", "5", "500", "100", "100",
            "2", "80", false);
        assertTransition(original, "7.000000", "660.000000", "94.285714", "ORIGINAL_SALE_COST", false);

        CostTransition fallback = calculate("SALE_RETURN_IN", "5", "500", "100", "100",
            "2", null, true);
        assertTransition(fallback, "7.000000", "700.000000", "100.000000",
            "CURRENT_AVERAGE_ESTIMATED", true);
    }

    @Test
    void valuesStocktakeGainAndLossAtCurrentAverage() {
        CostTransition gain = calculate("STOCKTAKE_GAIN", "4", "400", "100", "90", "1", null, false);
        assertTransition(gain, "5.000000", "500.000000", "100.000000", "CURRENT_AVERAGE", false);

        CostTransition loss = calculate("STOCKTAKE_LOSS", "4", "400", "100", "90", "-1", null, false);
        assertTransition(loss, "3.000000", "300.000000", "100.000000", "CURRENT_AVERAGE", false);
    }

    @Test
    void freezesTransferOutAndInheritsExactlyAtDestination() {
        CostTransition outbound = calculate("TRANSFER_OUT", "10", "1000", "100", "90",
            "-3", null, false);
        assertTransition(outbound, "7.000000", "700.000000", "100.000000",
            "TRANSFER_SOURCE_SNAPSHOT", false);

        CostTransition inbound = calculate("TRANSFER_IN", "2", "220", "110", "110",
            "3", "100", false);
        assertTransition(inbound, "5.000000", "520.000000", "104.000000",
            "INHERITED_TRANSFER_COST", false);

        CostTransition negative = calculate("TRANSFER_IN", "-2", "-180", "90", "90",
            "3", "100", false);
        assertTransition(negative, "1.000000", "100.000000", "100.000000",
            "TRANSFER_NEGATIVE_SETTLEMENT", false);
        assertThat(negative.varianceAmountMinor()).isEqualByComparingTo("20.000000");
    }

    @Test
    void freezesOutboundCostAndClosesRoundingResidueAtZero() {
        CostTransition sale = calculate("SALE_OUT", "3", "301", "100.333333", "100", "-1", null, false);
        assertTransition(sale, "2.000000", "200.666667", "100.333334", "MOVING_AVERAGE", false);

        CostTransition closes = calculate("SALE_OUT", "1", "100.000001", "100", "100", "-1", null, false);
        assertTransition(closes, "0.000000", "0.000000", "100.000000", "MOVING_AVERAGE", false);
        assertThat(closes.varianceAmountMinor()).isEqualByComparingTo("-0.000001");
    }

    @Test
    void negativeSaleUsesLastAuditableCostAndMarksEstimated() {
        CostTransition result = calculate("SALE_OUT", "0", "0", "0", "90", "-2", null, false);
        assertTransition(result, "-2.000000", "-180.000000", "90.000000", "LAST_COST_ESTIMATED", true);
    }

    @Test
    void purchaseReturnUsesOriginalReceiptCostAndCannotCreateNegativeQuantity() {
        CostTransition result = calculate("PURCHASE_RETURN_OUT", "5", "500", "100", "100", "-2", "80", false);
        assertTransition(result, "3.000000", "340.000000", "113.333333", "ORIGINAL_RECEIPT_COST", false);

        assertThatThrownBy(() -> calculate("PURCHASE_RETURN_OUT", "1", "100", "100", "100",
            "-2", "80", false)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("CST-RETURN-QTY-INSUFFICIENT");
    }

    @Test
    void appliesExactReversalAndRejectsNonClosingZeroAmount() {
        BalanceSnapshot before = balance("2", "180", "90", "90");
        CostTransition reversal = CostingRules.calculate(new ValuationInput("REVERSAL", new BigDecimal("2"),
            new BigDecimal("90"), true, new BigDecimal("180"), new BigDecimal("5"), before));
        assertTransition(reversal, "4.000000", "360.000000", "90.000000", "REVERSAL", true);
        assertThat(reversal.varianceAmountMinor()).isEqualByComparingTo("5.000000");

        assertThatThrownBy(() -> CostingRules.calculate(new ValuationInput("REVERSAL", new BigDecimal("-2"),
            new BigDecimal("90"), false, new BigDecimal("-170"), BigDecimal.ZERO, before)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-REVERSAL-002");
        assertThatThrownBy(() -> CostingRules.calculate(new ValuationInput("REVERSAL", BigDecimal.ONE,
            null, false, null, null, before))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("CST-REVERSAL-001");
    }

    @Test
    void rejectsUnsupportedDirectionsMissingCostZeroDeltaAndOverflow() {
        assertThatThrownBy(() -> CostingRules.calculate(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> CostingRules.calculate(new ValuationInput("SALE_OUT", BigDecimal.ONE,
            null, false, null, null, null))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("CST-MOVEMENT-001");
        assertThatThrownBy(() -> CostingRules.calculate(new ValuationInput("UNKNOWN", BigDecimal.ONE,
            null, false, null, null, balance("0", "0", "0", "0"))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-MOVEMENT-001");
        assertThatThrownBy(() -> calculate("SALE_OUT", "1", "100", "100", "100", "1", null, false))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-MOVEMENT-002");
        assertThatThrownBy(() -> calculate("PURCHASE_RECEIPT_IN", "1", "100", "100", "100", "-1", "90", false))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-MOVEMENT-003");
        assertThatThrownBy(() -> calculate("SALE_OUT", "0", "0", "0", "0", "-1", null, false))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-COST-MISSING");
        assertThatThrownBy(() -> calculate("SALE_OUT", "1", "100", "100", "100", "0", null, false))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-QTY-001");
        assertThatThrownBy(() -> calculate("SALE_OUT", "1.0000001", "100", "100", "100", "-1", null, false))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-QTY-002");
        assertThatThrownBy(() -> calculate("SALE_OUT", "1", "10000000000000000000", "100", "100", "-1", null, false))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-MONEY-001");
        assertThatThrownBy(() -> calculate("SALE_OUT", "1", "100", "-1", "100", "-1", null, false))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-MONEY-002");
    }

    @Test
    void validatesPurchaseConversionAndUlid() {
        assertThatThrownBy(() -> CostingRules.purchaseBaseUnitCost(-1, 1, 1))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-PURCHASE-001");
        assertThatThrownBy(() -> CostingRules.purchaseBaseUnitCost(1, 0, 1))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-PURCHASE-001");
        assertThatThrownBy(() -> CostingRules.purchaseBaseUnitCost(1, 1, 0))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-PURCHASE-001");
        CostingRules.requireUlid("01K2A000000000000000000001", "id");
        assertThatThrownBy(() -> CostingRules.requireUlid("bad", "id"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-ID-001");
        assertThatThrownBy(() -> CostingRules.requireUlid(null, "id"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-ID-001");
    }

    private static CostTransition calculate(String type, String quantity, String amount, String average,
                                             String last, String delta, String unit, boolean estimated) {
        return CostingRules.calculate(new ValuationInput(type, decimal(delta), nullable(unit), estimated,
            null, null, balance(quantity, amount, average, last)));
    }

    private static BalanceSnapshot balance(String quantity, String amount, String average, String last) {
        boolean known = decimal(quantity).signum() != 0 || decimal(amount).signum() != 0
            || decimal(average).signum() != 0 || decimal(last).signum() != 0;
        return new BalanceSnapshot(decimal(quantity), decimal(amount), decimal(average), decimal(last), known);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal nullable(String value) {
        return value == null ? null : decimal(value);
    }

    private static void assertTransition(CostTransition result, String quantity, String amount,
                                         String average, String method, boolean estimated) {
        assertThat(result.quantityAfter()).isEqualByComparingTo(quantity);
        assertThat(result.amountAfterMinor()).isEqualByComparingTo(amount);
        assertThat(result.averageUnitCostAfterMinor()).isEqualByComparingTo(average);
        assertThat(result.valuationMethod()).isEqualTo(method);
        assertThat(result.costEstimated()).isEqualTo(estimated);
    }
}
