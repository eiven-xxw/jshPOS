package com.jingshanghui.pos.foundation.application.port;

/**
 * 门店行业模板切换前的跨 Owner 失败关闭守卫。
 *
 * <p>Foundation 只负责按顺序调用守卫，不读取下游 Owner 私有表。</p>
 */
public interface StoreIndustryTransitionGuardPort {

    void requireCanActivate(IndustryTransition transition);

    /** 不可变的门店模板切换意图。 */
    record IndustryTransition(Long storeId, Long fromTemplateId, String fromIndustry,
                              Long toTemplateId, String toIndustry) {
    }
}
