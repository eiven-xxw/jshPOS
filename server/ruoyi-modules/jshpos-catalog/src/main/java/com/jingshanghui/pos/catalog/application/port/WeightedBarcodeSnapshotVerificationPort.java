package com.jingshanghui.pos.catalog.application.port;

import java.math.BigDecimal;
import java.time.Instant;

/** Catalog Owner 对成交计量快照提供的只读验真端口。 */
public interface WeightedBarcodeSnapshotVerificationPort {

    /** 按冻结模板和条码重新验算，不读取当前售价或改写任何订单事实。 */
    void verify(Long storeId, FrozenMeasurement snapshot);

    /** POS 冻结并随订单同步的计量事实；不携带 tenant_id。 */
    record FrozenMeasurement(Long skuId, String skuCode, Long unitId, String rawBarcode,
                             String encodedValue, BigDecimal quantity, long amountMinor,
                             long unitPriceMinor, String currency, Long templateId,
                             int templateVersion, String templateSha256, String parseSha256,
                             boolean roundingApplied, Instant occurredAt) {
    }
}
