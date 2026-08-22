package com.jingshanghui.pos.catalog.application.port;

import java.time.Instant;
import java.util.List;

/** ShelfLabel Owner 读取 Pricing/Catalog 权威快照的只读端口。 */
public interface ShelfLabelSourcePort {

    /** 读取指定价格簿中已冻结的商品、单位、条码和价格项快照。 */
    List<PriceSource> listPriceSources(String tenantId, Long priceBookId);

    /** 解析当前或排除指定价格簿后的权威门店价；没有有效价格时返回空。 */
    Long resolveAmount(String tenantId, Long skuId, Long unitId, Long storeId,
                       Instant effectiveAt, Long excludedPriceBookId);

    /**
     * 价签来源价格项。
     *
     * @param priceItemId 价格项主键
     * @param skuId SKU 主键
     * @param skuCode SKU 编码
     * @param productName 商品名称快照
     * @param unitId 单位主键
     * @param unitName 单位名称快照
     * @param barcode 首选有效条码，保留前导零
     * @param amountMinor 价格，单位为分
     * @param currency 币种
     * @param effectiveFrom 生效时间
     * @param effectiveTo 失效时间
     */
    record PriceSource(Long priceItemId, Long skuId, String skuCode, String productName,
                       Long unitId, String unitName, String barcode, Long amountMinor,
                       String currency, Instant effectiveFrom, Instant effectiveTo) {
    }
}
