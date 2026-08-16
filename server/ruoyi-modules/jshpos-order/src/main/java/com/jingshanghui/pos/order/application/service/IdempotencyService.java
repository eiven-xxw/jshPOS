package com.jingshanghui.pos.order.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.order.application.model.OrderViews.IdempotencyView;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final OrderMapper mapper;
    private final UlidGenerator ulids;
    private final ObjectMapper objectMapper;

    public <T> T find(String tenantId, String commandType, String key, String requestHash, Class<T> resultType) {
        IdempotencyView existing = mapper.findIdempotency(tenantId, commandType, key);
        if (existing == null) {
            return null;
        }
        if (!existing.requestSha256().equals(requestHash)) {
            throw new ServiceException("IDEMPOTENCY_KEY_REUSED: 相同幂等键对应不同请求", 409);
        }
        try {
            return objectMapper.readValue(existing.resultJson(), resultType);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("IDEMPOTENCY_RESULT_CORRUPT: 已保存结果无法读取", 500);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(String tenantId, String commandType, String commandId, String key, String requestHash,
                     String aggregateId, Object result, LocalDateTime at) {
        try {
            mapper.insertIdempotency(tenantId, ulids.next(), commandType, commandId, key, requestHash,
                aggregateId, "CREATED", objectMapper.writeValueAsString(result), at);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("IDEMPOTENCY_RESULT_INVALID: 命令结果无法持久化", 500);
        }
    }
}
