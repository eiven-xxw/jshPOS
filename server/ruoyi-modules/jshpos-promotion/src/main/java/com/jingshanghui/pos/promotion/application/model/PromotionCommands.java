package com.jingshanghui.pos.promotion.application.model;

import com.jingshanghui.pos.promotion.domain.PromotionModels.BasketLine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.RuleVersion;

import java.time.OffsetDateTime;
import java.util.List;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine.ActionType;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine.PaymentMethod;

/** 促销应用层命令；所有命令均刻意不包含 tenant_id。 */
public final class PromotionCommands {
    private PromotionCommands() {
    }

    /**
     * 创建规则身份和首个版本。
     *
     * @param commandId 命令ULID
     * @param ruleId 规则ULID
     * @param ruleVersionId 规则版本ULID
     * @param ruleCode 规则编码
     * @param name 规则名称
     * @param definition 规则定义
     * @param correlationId 关联ULID
     */
    public record CreateRule(String commandId, String ruleId, String ruleVersionId, String ruleCode,
                             String name, RuleVersion definition, String correlationId) {
    }

    /**
     * 版本状态命令。
     *
     * @param commandId 命令ULID
     * @param ruleId 规则ULID
     * @param ruleVersionId 规则版本ULID
     * @param expectedVersion 期望乐观锁版本
     * @param reason 原因
     * @param correlationId 关联ULID
     */
    public record StateCommand(String commandId, String ruleId, String ruleVersionId, int expectedVersion,
                               String reason, String correlationId) {
    }

    /**
     * 促销询价命令。
     *
     * @param pricingRequestId 询价幂等ULID
     * @param storeId 门店
     * @param terminalId 终端
     * @param channel 渠道
     * @param businessTime 带时区业务时间
     * @param currency 币种
     * @param packageVersion 离线规则包版本
     * @param lines 冻结价格行
     * @param correlationId 关联ULID
     */
    public record Quote(String pricingRequestId, Long storeId, String terminalId, String channel,
                        OffsetDateTime businessTime, String currency, long packageVersion,
                        List<BasketLine> lines, String correlationId) {
        public Quote { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    /**
     * 人工优惠请求；审批人不得由请求体声明，超阈值后必须由另一个已认证主体调用审批命令。
     *
     * @param commandId 幂等命令 ULID
     * @param authorizationId 人工授权 ULID
     * @param quoteId 原始报价 ULID
     * @param actionType 手工改价、整单优惠或抹零
     * @param lineId 行改价目标
     * @param amountOrRate 分金额、折扣率或抹零倍数
     * @param paymentMethod 支付方式，仅 CASH 可抹零
     * @param expectedQuoteFingerprint 当前报价指纹
     * @param reasonCode 原因码
     * @param reasonText 原因说明
     * @param correlationId 关联 ULID
     */
    public record ManualAuthorize(String commandId, String authorizationId, String quoteId,
                                  ActionType actionType, String lineId, String amountOrRate,
                                  PaymentMethod paymentMethod, String expectedQuoteFingerprint,
                                  String reasonCode, String reasonText, String correlationId) { }

    /**
     * 超阈值人工优惠复核命令。
     *
     * @param commandId 幂等命令 ULID
     * @param authorizationId 待复核授权 ULID
     * @param expectedPreviewFingerprint 待复核结果摘要
     * @param reason 复核说明
     * @param correlationId 关联 ULID
     */
    public record ManualApprove(String commandId, String authorizationId, String expectedPreviewFingerprint,
                                String reason, String correlationId) { }
}
