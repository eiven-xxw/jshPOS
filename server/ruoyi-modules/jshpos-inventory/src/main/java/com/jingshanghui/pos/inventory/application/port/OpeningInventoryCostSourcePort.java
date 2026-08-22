package com.jingshanghui.pos.inventory.application.port;

import java.math.BigDecimal;

/** Costing Owner 只读获取已冻结的期初库存成本来源，避免从客户端或库存流水猜测成本。 */
public interface OpeningInventoryCostSourcePort {
    OpeningCostSource requireOpeningLine(String sourceLineId);

    /**
     * 金额以 CNY 最小货币单位计量并保留六位小数。
     * @param batchId 迁移批次 ULID
     * @param rowId 期初库存来源行 ULID
     * @param skuId Catalog SKU 主键
     * @param baseUnitId 冻结基础单位主键
     * @param baseQuantity 基础单位精确数量
     * @param unitCostMinor 每基础单位成本，CNY 分并保留六位小数
     * @param currencyCode 冻结币种，商业 V1 为 CNY
     */
    record OpeningCostSource(String batchId, String rowId, Long skuId, Long baseUnitId,
                             BigDecimal baseQuantity, BigDecimal unitCostMinor, String currencyCode) {
    }
}
