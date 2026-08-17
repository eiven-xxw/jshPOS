package com.jingshanghui.pos.promotion.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;

/** Gate 5A REST 输入；不暴露 tenant_id、计算结果或服务端规则候选。 */
public final class PromotionRequests {
    public static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private PromotionRequests() { }

    /**
     * 创建规则请求。
     * @param commandId 命令ULID @param ruleId 规则ULID @param ruleVersionId 规则版本ULID
     * @param ruleCode 规则编码 @param name 规则名称 @param definition 白名单规则定义
     * @param correlationId 关联ULID
     */
    public record Create(@NotBlank @Pattern(regexp=ULID) String commandId,
                         @NotBlank @Pattern(regexp=ULID) String ruleId,
                         @NotBlank @Pattern(regexp=ULID) String ruleVersionId,
                         @NotBlank @Pattern(regexp="^[A-Z0-9][A-Z0-9_-]{0,63}$") String ruleCode,
                         @NotBlank @Size(max=128) String name,
                         @NotNull @Valid Definition definition,
                         @NotBlank @Pattern(regexp=ULID) String correlationId) { }

    /**
     * 状态变更请求。
     * @param commandId 命令ULID @param expectedVersion 期望乐观锁版本
     * @param reason 变更原因 @param correlationId 关联ULID
     */
    public record State(@NotBlank @Pattern(regexp=ULID) String commandId,
                        @Min(0) int expectedVersion,
                        @NotBlank @Size(max=256) String reason,
                        @NotBlank @Pattern(regexp=ULID) String correlationId) { }

    /**
     * 白名单规则定义。
     * @param ruleType 规则类型 @param priority 优先级 @param stackMode 叠加模式
     * @param exclusiveGroup 互斥组 @param effectiveFrom 生效时间 @param effectiveTo 失效时间
     * @param scope 作用域 @param benefit 优惠参数
     */
    public record Definition(@NotBlank String ruleType, @Min(-100000) @Max(100000) int priority,
                             @NotBlank String stackMode, @Size(max=64) String exclusiveGroup,
                             @NotNull OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                             @NotNull @Valid Scope scope, @NotNull @Valid Benefit benefit) { }

    /**
     * 规则适用范围。
     * @param skuIds SKU集合 @param categoryIds 分类集合 @param brandIds 品牌集合
     * @param storeIds 门店集合 @param channels 渠道集合 @param businessDays ISO业务星期集合
     */
    public record Scope(@Size(max=256) List<@Pattern(regexp="^[1-9][0-9]{0,18}$") String> skuIds,
                        @Size(max=256) List<@Pattern(regexp="^[1-9][0-9]{0,18}$") String> categoryIds,
                        @Size(max=256) List<@Pattern(regexp="^[1-9][0-9]{0,18}$") String> brandIds,
                        @Size(max=256) List<@Pattern(regexp="^[1-9][0-9]{0,18}$") String> storeIds,
                        @Size(max=16) List<String> channels,
                        @Size(max=7) List<@Min(1) @Max(7) Integer> businessDays) { }

    /**
     * 规则优惠参数。
     * @param amountMinor 优惠金额最小货币单位 @param discountRate 精确折扣率 @param nth 第N件序号
     * @param thresholdMinor 满额门槛 @param thresholdQuantity 满件门槛
     * @param bundlePriceMinor 组合价 @param bundleComponents 组合组件
     */
    public record Benefit(@PositiveOrZero Long amountMinor,
                          @Pattern(regexp="^0(?:\\.[0-9]{1,8})?$|^1(?:\\.0{1,8})?$") String discountRate,
                          @Min(2) @Max(100) Integer nth, @PositiveOrZero Long thresholdMinor,
                          @Pattern(regexp="^[0-9]+(?:\\.[0-9]{1,6})?$") String thresholdQuantity,
                          @PositiveOrZero Long bundlePriceMinor,
                          @Size(max=32) List<@Valid BundleComponent> bundleComponents) { }

    /**
     * 组合价组件。
     * @param skuId SKU标识 @param quantity 精确数量
     */
    public record BundleComponent(@NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String skuId,
                                  @NotBlank @Pattern(regexp="^[0-9]+(?:\\.[0-9]{1,6})?$") String quantity) { }

    /**
     * 促销询价请求。
     * @param pricingRequestId 询价幂等ULID @param storeId 门店 @param terminalId 终端
     * @param channel 渠道 @param businessTime 带时区业务时间 @param currency ISO币种
     * @param packageVersion 离线规则包版本 @param lines 冻结价格购物行 @param correlationId 关联ULID
     */
    public record Quote(@NotBlank @Pattern(regexp=ULID) String pricingRequestId,
                        @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String storeId,
                        @NotBlank @Size(max=64) String terminalId,
                        @NotBlank String channel, @NotNull OffsetDateTime businessTime,
                        @NotBlank String currency, @Positive long packageVersion,
                        @NotEmpty @Size(max=500) List<@Valid QuoteLine> lines,
                        @NotBlank @Pattern(regexp=ULID) String correlationId) { }

    /**
     * 促销询价购物行。
     * @param lineId 购物行ULID @param lineNo 稳定行号 @param skuId SKU标识
     * @param categoryId 分类标识 @param brandId 品牌标识 @param quantity 精确数量
     * @param unitPriceMinor 冻结单价最小货币单位
     */
    public record QuoteLine(@NotBlank @Pattern(regexp=ULID) String lineId, @Min(1) @Max(500) int lineNo,
                            @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String skuId,
                            @Pattern(regexp="^[1-9][0-9]{0,18}$") String categoryId,
                            @Pattern(regexp="^[1-9][0-9]{0,18}$") String brandId,
                            @NotBlank @Pattern(regexp="^[0-9]+(?:\\.[0-9]{1,6})?$") String quantity,
                            @PositiveOrZero long unitPriceMinor) { }

    /**
     * 发布门店规则包请求。
     * @param storeId 门店 @param packageVersion 单调包版本 @param previousVersion 前一包版本
     * @param expiresAt 过期时间 @param correlationId 关联ULID
     */
    public record PublishPackage(@NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String storeId,
                                 @Positive long packageVersion, @PositiveOrZero long previousVersion,
                                 @NotNull Instant expiresAt,
                                 @NotBlank @Pattern(regexp=ULID) String correlationId) { }

    /**
     * 人工优惠请求；审批人由后续独立认证请求确定。
     * @param commandId 幂等命令 @param authorizationId 授权标识 @param quoteId 报价
     * @param actionType 动作 @param lineId 行改价目标 @param amountOrRate 金额折扣率或抹零倍数
     * @param paymentMethod 支付方式 @param expectedQuoteFingerprint 当前报价摘要
     * @param reasonCode 原因码 @param reasonText 原因说明 @param correlationId 关联标识
     */
    public record ManualAuthorize(@NotBlank @Pattern(regexp=ULID) String commandId,
                                  @NotBlank @Pattern(regexp=ULID) String authorizationId,
                                  @NotBlank @Pattern(regexp=ULID) String quoteId,
                                  @NotBlank String actionType,
                                  @Pattern(regexp=ULID) String lineId,
                                  @NotBlank @Size(max=32) String amountOrRate,
                                  @NotBlank String paymentMethod,
                                  @NotBlank @Pattern(regexp="^[a-f0-9]{64}$") String expectedQuoteFingerprint,
                                  @NotBlank @Pattern(regexp="^[A-Z0-9_]{1,32}$") String reasonCode,
                                  @NotBlank @Size(max=256) String reasonText,
                                  @NotBlank @Pattern(regexp=ULID) String correlationId) { }

    /**
     * 超阈值人工优惠复核请求。
     * @param commandId 幂等命令 @param expectedPreviewFingerprint 预检摘要
     * @param reason 复核说明 @param correlationId 关联标识
     */
    public record ManualApprove(@NotBlank @Pattern(regexp=ULID) String commandId,
                                @NotBlank @Pattern(regexp="^[a-f0-9]{64}$") String expectedPreviewFingerprint,
                                @NotBlank @Size(max=256) String reason,
                                @NotBlank @Pattern(regexp=ULID) String correlationId) { }

    /** 冻结不可变成交优惠快照。 */
    public record FreezeSnapshot(@NotBlank @Pattern(regexp=ULID) String commandId,
                                 @NotBlank @Pattern(regexp=ULID) String snapshotId,
                                 @NotBlank @Pattern(regexp=ULID) String orderId,
                                 @NotBlank @Pattern(regexp=ULID) String quoteId,
                                 @NotBlank @Pattern(regexp="^[a-f0-9]{64}$") String quoteFingerprint,
                                 @NotBlank @Pattern(regexp=ULID) String correlationId) { }

    /** 按原成交快照追加退款优惠恢复。 */
    public record AllocateRefund(@NotBlank @Pattern(regexp=ULID) String commandId,
                                 @NotBlank @Pattern(regexp=ULID) String refundId,
                                 @NotEmpty @Size(max=500) List<@Valid RefundLine> lines,
                                 @NotBlank @Pattern(regexp=ULID) String correlationId) { }

    /** 退款行只接受原成交行标识和精确数量。 */
    public record RefundLine(@NotBlank @Pattern(regexp=ULID) String lineId,
                             @NotBlank @Pattern(regexp="^[0-9]+(?:\\.[0-9]{1,6})?$") String quantity) { }
}
