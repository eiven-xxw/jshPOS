package com.jingshanghui.pos.reporting.infrastructure.export;

import com.jingshanghui.pos.reporting.application.model.ReportingViews.*;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.ReconciliationView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** CSV 字段白名单提取、确定性排序、注入防护和水印回归。 */
class ReportCsvEncoderTest {
    private final ReportCsvEncoder encoder = new ReportCsvEncoder();

    @Test void encodesSalesWithWatermarkQuotesAndStableFields() {
        var row = new SalesDailyView(LocalDate.of(2026,8,17), 1L, 11L, "=terminal", 7L, "CNY",
            1,0,0,100,10,0,90,0,90,0,0,1,"CURRENT");
        String csv = new String(encoder.sales("tenant_alpha", "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            Set.of("terminalId","grossMinor","businessDate"), List.of(row), Instant.EPOCH), StandardCharsets.UTF_8);
        assertThat(csv).contains("tenant=\"tenant_alpha\"").contains("\"businessDate\",\"grossMinor\",\"terminalId\"")
            .contains("\"'=terminal\"").doesNotContain("\r\n=terminal");
    }

    @Test void encodesAllSalesAndInventoryFieldsAndRejectsUnknown() {
        var sales = new SalesDailyView(LocalDate.of(2026,8,17),1L,11L,"T1",7L,"CNY",1,2,3,4,5,6,5,7,8,9,10,11,"CURRENT");
        Set<String> salesFields = Set.of("businessDate","storeId","terminalId","cashierId","currency","orderCount",
            "cancelledOrderCount","returnCount","grossMinor","discountMinor","surchargeMinor","receivableMinor",
            "refundMinor","cashReceivedMinor","cashRefundedMinor","shiftDifferenceMinor","promotionSnapshotCount","projectionStatus");
        assertThat(encoder.sales("t","01ARZ3NDEKTSV4RRFFQ69G5FAV",salesFields,List.of(sales),Instant.EPOCH)).isNotEmpty();
        var inventory = new InventoryCostDailyView(LocalDate.of(2026,8,17),1L,11L,"01ARZ3NDEKTSV4RRFFQ69G5FAW",9L,"CNY",
            d("1"),d("2"),d("3"),d("4"),d("5"),d("6"),d("7"),d("8"),d("9"),d("10"),d("11"),d("12"),"CURRENT");
        Set<String> inventoryFields = Set.of("businessDate","storeId","warehouseId","skuId","currency","onHandDelta",
            "availableDelta","reservedDelta","ledgerQuantityDelta","purchaseQuantityDelta","stocktakeQuantityDelta",
            "transferQuantityDelta","inventoryValueDeltaMinor","cogsDeltaMinor","purchaseCostDeltaMinor",
            "stocktakeCostDeltaMinor","transferCostDeltaMinor","projectionStatus");
        assertThat(encoder.inventoryCost("t","01ARZ3NDEKTSV4RRFFQ69G5FAV",inventoryFields,List.of(inventory),Instant.EPOCH)).isNotEmpty();
        assertThatThrownBy(() -> encoder.sales("t","01ARZ3NDEKTSV4RRFFQ69G5FAV",Set.of("unknown"),List.of(sales),Instant.EPOCH))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.inventoryCost("t","01ARZ3NDEKTSV4RRFFQ69G5FAV",Set.of("unknown"),List.of(inventory),Instant.EPOCH))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void encodesPaymentReconciliationWithStableWhitelistAndFormulaProtection() {
        var row=new ReconciliationView("01ARZ3NDEKTSV4RRFFQ69G5FAV","01ARZ3NDEKTSV4RRFFQ69G5FAV","REFUND",
            "01ARZ3NDEKTSV4RRFFQ69G5FAW","01ARZ3NDEKTSV4RRFFQ69G5FAX",LocalDate.of(2026,8,17),
            1L,11L,"=terminal","CNY",90L,91L,"SUCCEEDED","SUCCEEDED",LocalDate.of(2026,8,17),
            LocalDate.of(2026,8,17),"AMOUNT_MISMATCH","ASSIGNED",8L,Instant.EPOCH,Instant.EPOCH,2);
        Set<String> fields=Set.of("reconciliationId","factType","businessDate","storeId","terminalId",
            "currency","internalAmountMinor","billAmountMinor","internalStatus","billStatus",
            "internalBusinessDate","billBusinessDate","differenceType","handlingState","handlerId");
        String csv=new String(encoder.paymentReconciliation("tenant_alpha","01ARZ3NDEKTSV4RRFFQ69G5FAY",
            fields,List.of(row),Instant.EPOCH),StandardCharsets.UTF_8);
        assertThat(csv).contains("tenant=\"tenant_alpha\"").contains("\"'=terminal\"")
            .contains("\"AMOUNT_MISMATCH\"").contains("\"ASSIGNED\"");
        assertThatThrownBy(() -> encoder.paymentReconciliation("tenant_alpha","01ARZ3NDEKTSV4RRFFQ69G5FAY",
            Set.of("providerSecret"),List.of(row),Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }
    private BigDecimal d(String value) { return new BigDecimal(value); }
}
