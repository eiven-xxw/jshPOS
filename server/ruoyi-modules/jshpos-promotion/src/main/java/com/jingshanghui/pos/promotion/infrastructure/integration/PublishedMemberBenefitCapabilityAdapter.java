package com.jingshanghui.pos.promotion.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.port.PublishedConfigReadPort;
import com.jingshanghui.pos.promotion.application.port.MemberBenefitCapabilityPort;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

/** 从 Foundation 已发布配置读取能力，解析失败时失败关闭。 */
@Component
@RequiredArgsConstructor
public class PublishedMemberBenefitCapabilityAdapter implements MemberBenefitCapabilityPort {
    private static final String CODE="MEMBER_BENEFIT_CAPABILITY";
    private final PublishedConfigReadPort configs;
    private final ObjectMapper objectMapper;

    @Override public Capability resolve(Long storeId) {
        return configs.find(CODE,storeId).map(value -> {
            try {
                JsonNode node=objectMapper.readTree(value.contentJson());
                return new Capability(node.path("enabled").asBoolean(false),
                    node.path("promotionStackingAllowed").asBoolean(false),value.configVersionId(),
                    value.contentSha256());
            } catch (Exception exception) {
                throw new ServiceException("PRM-MEMBER-007: 会员权益能力配置损坏",409);
            }
        }).orElse(new Capability(false,false,0,"0".repeat(64)));
    }
}
