package com.jingshanghui.pos.procurement.application.port;

import java.math.BigDecimal;

/**
 * 成本模块读取已确认采购价格、冻结换算和原收货关系的受控端口。
 *
 * <p>端口不接受 tenant_id，也不允许调用者提交价格；不可见或未确认事实必须失败关闭。</p>
 */
public interface ProcurementCostSourcePort {

    ReceiptCostSource requireReceiptLine(String receiptLineId);

    ReturnCostSource requireReturnLine(String returnLineId);

    /** 已确认收货行的基础单位成本来源，金额以最小货币单位计量。 */
    record ReceiptCostSource(String receiptLineId, String receiptId, String orderLineId,
                             Long skuId, Long baseUnitId, BigDecimal baseQuantity,
                             long purchaseUnitPriceMinor, long conversionNumerator,
                             long conversionDenominator, String currencyCode) {
    }

    /** 已入账采购退货行及其不可变原收货行关系。 */
    record ReturnCostSource(String returnLineId, String purchaseReturnId,
                            String originalReceiptLineId, Long skuId, Long baseUnitId,
                            BigDecimal baseQuantity, String currencyCode) {
    }
}
