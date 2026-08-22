package com.jingshanghui.pos.catalog.application.model;

import java.time.Instant;

/** Catalog Owner 批次效期策略命令与只读视图。 */
public final class LotPolicyModels {
    private LotPolicyModels() { }

    /**
     * 发布不可变策略版本的命令；租户和行业不允许由客户端携带。
     *
     * @param policyVersionId 策略版本 ULID
     * @param storeId 门店平台主键
     * @param skuId SKU 平台主键
     * @param enabled 是否启用
     * @param expiryBasis 日期基准
     * @param shelfLifeDays 保质期天数
     * @param nearExpiryDays 临期阈值
     * @param effectiveFrom 生效时刻
     * @param correlationId 关联标识
     */
    public record PublishCommand(String policyVersionId, Long storeId, Long skuId, boolean enabled,
                                 String expiryBasis, Integer shelfLifeDays, int nearExpiryDays,
                                 Instant effectiveFrom, String correlationId) {
    }

    /**
     * 已发布批次策略视图。
     *
     * @param policyVersionId 策略版本 ULID
     * @param storeId 门店主键
     * @param skuId SKU 主键
     * @param enabled 是否启用
     * @param expiryBasis 日期基准
     * @param shelfLifeDays 保质期天数
     * @param nearExpiryDays 临期阈值
     * @param industry 可信行业代码
     * @param templateVersionId 行业模板版本主键
     * @param effectiveFrom 生效时刻
     * @param contentSha256 内容摘要
     * @param state 发布状态
     */
    public record PolicyView(String policyVersionId, Long storeId, Long skuId, boolean enabled,
                             String expiryBasis, Integer shelfLifeDays, int nearExpiryDays, String industry,
                             Long templateVersionId, Instant effectiveFrom, String contentSha256, String state) {
    }
}

