package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.ManualPolicyRow;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine.Policy;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将 Gate 0 已发布配置严格解码为 PRM-002 阈值快照。 */
@Component
@RequiredArgsConstructor
public class ManualPolicyCodec {
    private static final Set<String> FIELDS = Set.of("policyType", "withoutApprovalMinor",
        "withApprovalMinor", "minimumLinePayableMinor", "maximumRoundingMinor", "roundingMultiplesMinor");
    private final ObjectMapper objectMapper;

    /** 拒绝未知字段、摘要不一致、浮点阈值和缺失字段。 */
    public Policy decode(ManualPolicyRow row) {
        if (row == null) throw new ServiceException("PRM-AUTH-001: 门店未绑定人工优惠阈值策略", 503);
        try {
            Map<String, Object> content = objectMapper.readValue(row.contentJson(), new TypeReference<>() { });
            if (!content.keySet().equals(FIELDS) || !"PROMOTION_MANUAL_AUTHORITY".equals(content.get("policyType"))
                || !CanonicalJson.from(content).sha256().equals(row.contentSha256())) {
                throw new ServiceException("PRM-AUTH-002: 人工优惠策略摘要或结构无效", 500);
            }
            List<Long> multiples = new ArrayList<>();
            for (Object value : requireList(content, "roundingMultiplesMinor")) {
                multiples.add(requireLong(value, "roundingMultiplesMinor"));
            }
            return new Policy(row.policyVersionId(), row.contentSha256(),
                requireLong(content.get("withoutApprovalMinor"), "withoutApprovalMinor"),
                requireLong(content.get("withApprovalMinor"), "withApprovalMinor"),
                requireLong(content.get("minimumLinePayableMinor"), "minimumLinePayableMinor"),
                requireLong(content.get("maximumRoundingMinor"), "maximumRoundingMinor"), multiples);
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("PRM-AUTH-002: 人工优惠策略摘要或结构无效", 500);
        }
    }

    private long requireLong(Object value, String field) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
            || value instanceof Long || value instanceof BigInteger)) {
            throw new ServiceException("PRM-AUTH-003: " + field + "必须为整数", 500);
        }
        try {
            return value instanceof BigInteger integer ? integer.longValueExact() : ((Number) value).longValue();
        } catch (ArithmeticException exception) {
            throw new ServiceException("PRM-AUTH-003: " + field + "整数越界", 500);
        }
    }

    private List<?> requireList(Map<String, Object> content, String field) {
        Object value = content.get(field);
        if (!(value instanceof List<?> list)) throw new ServiceException("PRM-AUTH-003: " + field + "必须为数组", 500);
        return list;
    }
}
