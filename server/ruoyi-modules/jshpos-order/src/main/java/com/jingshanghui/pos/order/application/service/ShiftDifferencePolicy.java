package com.jingshanghui.pos.order.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.port.PublishedConfigReadPort;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

/** 从 Foundation 只读端口解析交班现金差异阈值，缺失策略时采用最安全的零阈值。 */
@Component
@RequiredArgsConstructor
public class ShiftDifferencePolicy {

    static final String TEMPLATE_CODE = "SHIFT_CASH_DIFFERENCE";
    private final PublishedConfigReadPort configs;
    private final ObjectMapper objectMapper;

    public long approvalThresholdMinor(Long storeId) {
        return configs.find(TEMPLATE_CODE, storeId).map(config -> {
            try {
                JsonNode root = objectMapper.readTree(config.contentJson());
                JsonNode value = root.get("cashDifferenceApprovalMinor");
                if (value == null || !value.isIntegralNumber() || value.longValue() < 0) {
                    throw new ServiceException("SHIFT_POLICY_INVALID: 交班差异策略无效", 409);
                }
                return value.longValue();
            } catch (ServiceException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ServiceException("SHIFT_POLICY_INVALID: 交班差异策略无法解析", 409);
            }
        }).orElse(0L);
    }
}
