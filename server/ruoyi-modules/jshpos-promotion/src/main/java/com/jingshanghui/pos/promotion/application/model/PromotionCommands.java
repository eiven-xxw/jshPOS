package com.jingshanghui.pos.promotion.application.model;

import com.jingshanghui.pos.promotion.domain.PromotionModels.BasketLine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.RuleVersion;

import java.time.OffsetDateTime;
import java.util.List;

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
}
