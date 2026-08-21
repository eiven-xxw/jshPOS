package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ORD-004订单终局仲裁器。
 * 取消与成交竞争同一个数据库唯一键，确保并发到达顺序不会同时产生两个终局事实。
 */
@Service
@RequiredArgsConstructor
public class OrderFinalityGuardService {

    private final OrderMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveCancellation(String tenantId, String orderId, String sourceEventId,
                                    String requestHash, LocalDateTime at) {
        reserve(tenantId, orderId, "CANCELLED", sourceEventId, requestHash, at);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserveCompletion(String tenantId, String orderId, String commandId,
                                  String requestHash, LocalDateTime at) {
        if (mapper.countCancellationDisposition(tenantId, orderId) != 0) {
            throw new ServiceException(
                "ORDER_CANCELLATION_BLOCKED: 取消墓碑已存在，禁止后到成交覆盖取消事实", 409);
        }
        reserve(tenantId, orderId, "COMPLETED", commandId, requestHash, at);
    }

    private void reserve(String tenantId, String orderId, String finalityType, String sourceId,
                         String requestHash, LocalDateTime at) {
        try {
            mapper.insertOrderFinalityGuard(tenantId, orderId, finalityType, sourceId, requestHash, at);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException(
                "ORDER_FINALITY_CONFLICT: 订单取消或成交终局已由另一事务确定", 409);
        }
    }
}
