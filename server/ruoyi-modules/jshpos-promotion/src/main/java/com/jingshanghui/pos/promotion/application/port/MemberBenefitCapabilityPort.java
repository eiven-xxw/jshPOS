package com.jingshanghui.pos.promotion.application.port;

/** 会员权益能力开关只能来自可信已发布配置；缺失时默认关闭。 */
public interface MemberBenefitCapabilityPort {
    record Capability(boolean enabled, boolean promotionStackingAllowed,
                      long configVersion, String contentSha256) { }
    Capability resolve(Long storeId);
}
