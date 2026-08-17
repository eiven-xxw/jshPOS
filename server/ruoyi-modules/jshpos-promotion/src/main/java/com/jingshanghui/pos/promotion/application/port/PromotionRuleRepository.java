package com.jingshanghui.pos.promotion.application.port;

/** 简单规则身份表的 MyBatis-Plus 仓储端口。 */
public interface PromotionRuleRepository {
    /** 新增规则身份。 */
    void insert(RuleIdentity identity);
    /** 按可信租户读取规则身份。 */
    RuleIdentity find(String tenantId, String ruleId);

    /**
     * 规则身份。
     *
     * @param tenantId 可信租户
     * @param ruleId 规则ULID
     * @param ruleCode 规则编码
     * @param ruleName 规则名称
     * @param status 状态
     * @param createdBy 创建人
     */
    record RuleIdentity(String tenantId, String ruleId, String ruleCode, String ruleName,
                        String status, Long createdBy) {
    }
}
