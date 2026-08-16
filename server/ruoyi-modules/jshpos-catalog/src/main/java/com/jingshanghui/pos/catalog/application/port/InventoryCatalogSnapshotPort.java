package com.jingshanghui.pos.catalog.application.port;

/**
 * 库存与采购使用的商品单位只读快照端口。
 *
 * <p>SKU 状态和单位换算由商品 Owner 提供；调用方只能冻结快照，不得直接修改商品表。</p>
 */
public interface InventoryCatalogSnapshotPort {

    /** 取得 SKU 的启用基础单位，供库存盘点建立数量维度。 */
    SkuUnitSnapshot requirePrimaryUnit(Long skuId);

    /** 取得指定采购单位到基础单位的冻结换算。 */
    SkuUnitSnapshot requireUnit(Long skuId, Long unitId);

    /** 数量换算快照；分子/分母均为正整数，base = input × numerator ÷ denominator。 */
    record SkuUnitSnapshot(Long skuId, String skuCode, Long unitId, Long baseUnitId,
                           long numerator, long denominator, boolean primaryUnit) {
    }
}
