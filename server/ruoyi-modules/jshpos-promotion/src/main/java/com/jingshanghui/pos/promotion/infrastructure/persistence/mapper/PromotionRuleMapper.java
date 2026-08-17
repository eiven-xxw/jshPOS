package com.jingshanghui.pos.promotion.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jingshanghui.pos.promotion.infrastructure.persistence.entity.PromotionRuleEntity;

/** 仅处理简单 prm_rule 表，不承载版本、账本或历史事实。 */
public interface PromotionRuleMapper extends BaseMapper<PromotionRuleEntity> {
}
