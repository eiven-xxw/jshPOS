package com.jingshanghui.pos.catalog.application.port;

import java.util.List;

/**
 * 开业资料迁移写入 Catalog Owner 的受控端口。
 *
 * <p>端口不接收 tenant_id；商品先以 DRAFT 创建，只有迁移批次对账通过后才允许按批激活。</p>
 */
public interface BusinessMigrationCatalogPort {

    ProductMigrationResult importDraftProduct(ProductMigrationCommand command);

    ProductMigrationResult requireProduct(String batchId, String skuCode);

    int activateBatch(String batchId, String correlationId);

    /**
     * 预检后冻结的商品行，不允许携带租户标识。
     * @param batchId Migration Owner 生成的批次 ULID
     * @param rowId 冻结迁移行 ULID
     * @param rowSha256 规范行内容 SHA-256
     * @param spuCode 租户内 SPU 编码
     * @param skuCode 租户内 SKU 编码
     * @param name 商品名称
     * @param categoryCode 分类编码
     * @param categoryName 分类名称
     * @param brandCode 可空品牌编码
     * @param brandName 可空品牌名称
     * @param productType 商品计量类型
     * @param unitCode 基础单位编码
     * @param unitName 基础单位名称
     * @param decimalScale 数量允许小数位
     * @param ratioNumerator 相对基础单位换算分子
     * @param ratioDenominator 相对基础单位换算分母
     * @param barcodes 原样保留前导零的条码集合
     * @param correlationId 全链路关联标识
     */
    record ProductMigrationCommand(String batchId, String rowId, String rowSha256,
                                   String spuCode, String skuCode, String name,
                                   String categoryCode, String categoryName,
                                   String brandCode, String brandName,
                                   String productType, String unitCode, String unitName,
                                   int decimalScale, long ratioNumerator, long ratioDenominator,
                                   List<String> barcodes, String correlationId) {
        public ProductMigrationCommand {
            barcodes = barcodes == null ? List.of() : List.copyOf(barcodes);
        }
    }

    /**
     * Catalog Owner 返回的稳定商品和基础单位身份。
     * @param skuId Catalog Owner 分配的 SKU 主键
     * @param baseUnitId Catalog Owner 分配的基础单位主键
     * @param skuCode 冻结 SKU 编码
     * @param state 商品当前状态
     * @param rowSha256 已接收迁移行摘要
     * @param replay 是否返回既有幂等结果
     */
    record ProductMigrationResult(Long skuId, Long baseUnitId, String skuCode,
                                  String state, String rowSha256, boolean replay) {
    }
}
