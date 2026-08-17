package com.jingshanghui.pos.promotion.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingshanghui.pos.promotion.application.port.PromotionRuleRepository;
import com.jingshanghui.pos.promotion.infrastructure.persistence.entity.PromotionRuleEntity;
import com.jingshanghui.pos.promotion.infrastructure.persistence.mapper.PromotionRuleMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** MyBatis-Plus 简单规则身份仓储适配器。 */
@Repository
@RequiredArgsConstructor
public class MyBatisPromotionRuleRepository implements PromotionRuleRepository {
    private final PromotionRuleMapper mapper;

    @Override
    public void insert(RuleIdentity identity) {
        PromotionRuleEntity entity = new PromotionRuleEntity();
        entity.setTenantId(identity.tenantId());
        entity.setRuleId(identity.ruleId());
        entity.setRuleCode(identity.ruleCode());
        entity.setRuleName(identity.ruleName());
        entity.setStatus(identity.status());
        entity.setVersion(0);
        entity.setCreatedBy(identity.createdBy());
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(entity.getCreatedAt());
        if (mapper.insert(entity) != 1) throw new ServiceException("PRM-STORE-001: 规则身份写入失败", 409);
    }

    @Override
    public RuleIdentity find(String tenantId, String ruleId) {
        PromotionRuleEntity entity = mapper.selectOne(new LambdaQueryWrapper<PromotionRuleEntity>()
            .eq(PromotionRuleEntity::getTenantId, tenantId).eq(PromotionRuleEntity::getRuleId, ruleId));
        return entity == null ? null : new RuleIdentity(entity.getTenantId(), entity.getRuleId(),
            entity.getRuleCode(), entity.getRuleName(), entity.getStatus(), entity.getCreatedBy());
    }
}
