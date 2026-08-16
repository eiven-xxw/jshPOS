package com.jingshanghui.pos.payment.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.model.PaymentViews.IdempotencyView;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 支付、退款和对账命令共享的稳定幂等结果仓库。 */
@Service
@RequiredArgsConstructor
public class PaymentIdempotencyService {

    private final PaymentMapper mapper;
    private final UlidGenerator ulids;
    private final ObjectMapper objectMapper;

    public <T> T find(String tenantId, String commandType, String key, String requestHash, Class<T> resultType) {
        IdempotencyView existing = mapper.findIdempotency(tenantId, commandType, key);
        if (existing == null) return null;
        if (!existing.requestSha256().equals(requestHash)) {
            throw new ServiceException("PAY-IDEM-002: 相同幂等键对应不同请求", 409);
        }
        try {
            return objectMapper.readValue(existing.resultJson(), resultType);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PAY-IDEM-003: 已保存支付结果无法读取", 500);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(String tenantId, String commandType, String commandId, String key, String requestHash,
                     String aggregateId, Object result, LocalDateTime at) {
        try {
            mapper.insertIdempotency(ulids.next(), tenantId, commandType, commandId, key, requestHash,
                aggregateId, objectMapper.writeValueAsString(result), at);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PAY-IDEM-004: 支付命令结果无法持久化", 500);
        }
    }
}
