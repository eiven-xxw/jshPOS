package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.application.port.OrderDispositionSubmissionPort;
import com.jingshanghui.pos.order.domain.CanonicalHash;
import com.jingshanghui.pos.order.domain.OrderRules;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * ORD-004订单处置唯一写入服务。
 * 取消使用先到墓碑阻止后到成交；已成交订单只追加处置路由，状态和历史事实保持不变。
 */
@Service
@RequiredArgsConstructor
public class OrderDispositionService implements OrderDispositionSubmissionPort {

    private static final Set<String> ROUTES = Set.of("RETURN_REFUND_REQUIRED",
        "PAYMENT_REVERSAL_OBSERVATION_REQUIRED", "EXPLICIT_COMPENSATION_REQUIRED");

    private final OrderMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final OrderJournalService journal;
    private final OrderFinalityGuardService finalityGuard;

    @Override
    @Transactional
    public void record(String sourceEventId, String dispositionId, String orderId, Long storeId,
                       String terminalId, String shiftId, Long actorUserId, LocalDate businessDate,
                       String dispositionType, String fromStatus, String effectiveStatus,
                       String reasonCode, String reasonText, String authorizationRef,
                       String orderSnapshotSha256, String requestSha256, long aggregateVersion,
                       LocalDateTime occurredAt) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireShape(sourceEventId, dispositionId, orderId, terminalId, shiftId, businessDate,
            dispositionType, fromStatus, effectiveStatus, reasonCode, reasonText, authorizationRef,
            orderSnapshotSha256, requestSha256, aggregateVersion, occurredAt);
        if (storeId == null || !principal.userId().equals(actorUserId)) {
            throw new ServiceException("ORDER_DISPOSITION_CONTEXT_MISMATCH: 操作者或门店上下文不可信", 403);
        }
        authorizationService.requireStoreAccess(storeId);
        String expectedHash = requestHash(orderId, storeId, terminalId, actorUserId, shiftId,
            businessDate, dispositionType, fromStatus, effectiveStatus, reasonCode,
            reasonText.trim(), authorizationRef, orderSnapshotSha256);
        if (!expectedHash.equals(requestSha256)) {
            throw new ServiceException("ORDER_DISPOSITION_HASH_MISMATCH: 处置命令摘要不匹配", 409);
        }

        OrderView order = mapper.findOrder(principal.tenantId(), orderId);
        if ("CANCEL_BEFORE_COMPLETION".equals(dispositionType)) {
            if (order != null || !Set.of("DRAFT", "PENDING_PAYMENT").contains(fromStatus)
                || !"CANCELLED".equals(effectiveStatus) || authorizationRef != null) {
                throw new ServiceException("ORDER_CANCELLATION_BLOCKED: 成交已存在或取消状态无效", 409);
            }
            finalityGuard.reserveCancellation(principal.tenantId(), orderId, sourceEventId,
                requestSha256, occurredAt);
        } else {
            if (order == null || !ROUTES.contains(dispositionType)
                || !Set.of("CONFIRMED", "COMPLETED").contains(order.status())
                || !order.status().equals(fromStatus) || !fromStatus.equals(effectiveStatus)
                || !storeId.equals(order.storeId()) || !terminalId.equals(order.terminalId())
                || !orderSnapshotSha256.equals(order.snapshotSha256())) {
                throw new ServiceException("ORDER_DISPOSITION_REQUIRED: 成交事实只能追加受控反向处置路由", 409);
            }
            if (!"RETURN_REFUND_REQUIRED".equals(dispositionType)
                && (authorizationRef == null || authorizationRef.length() < 16)) {
                throw new ServiceException("PERMISSION_DENIED: 异常补偿路由缺少授权引用", 403);
            }
        }

        mapper.insertOrderDisposition(principal.tenantId(), sourceEventId, dispositionId, orderId,
            storeId, terminalId, shiftId, actorUserId, businessDate, dispositionType,
            fromStatus, effectiveStatus, reasonCode, reasonText.trim(), authorizationRef,
            orderSnapshotSha256, requestSha256, aggregateVersion, occurredAt);
        journal.audit(principal.tenantId(), "CANCEL_BEFORE_COMPLETION".equals(dispositionType)
                ? "ORDER_CANCELLED" : "ORDER_REVERSAL_ROUTED", "ORDER", orderId,
            principal.userId(), null, sourceEventId, fromStatus, effectiveStatus, null,
            requestSha256, reasonCode, occurredAt);
        journal.appendEvent(principal.tenantId(), "order.disposition", "order.disposition-recorded.v1",
            "ORDER_DISPOSITION", dispositionId, 1, sourceEventId,
            "{\"dispositionId\":\"" + dispositionId + "\",\"orderId\":\"" + orderId
                + "\",\"dispositionType\":\"" + dispositionType + "\"}", occurredAt);
    }

    private void requireShape(String sourceEventId, String dispositionId, String orderId,
                              String terminalId, String shiftId, LocalDate businessDate,
                              String dispositionType, String fromStatus, String effectiveStatus,
                              String reasonCode, String reasonText, String authorizationRef,
                              String orderSnapshotSha256, String requestSha256,
                              long aggregateVersion, LocalDateTime occurredAt) {
        OrderRules.requireUlid(sourceEventId, "sourceEventId");
        OrderRules.requireUlid(dispositionId, "dispositionId");
        OrderRules.requireUlid(orderId, "orderId");
        OrderRules.requireUlid(terminalId, "terminalId");
        OrderRules.requireUlid(shiftId, "shiftId");
        if (businessDate == null || occurredAt == null || aggregateVersion <= 0
            || dispositionType == null || fromStatus == null || effectiveStatus == null
            || reasonCode == null || !reasonCode.matches("^[A-Z][A-Z0-9_]{1,31}$")
            || reasonText == null || reasonText.isBlank() || reasonText.length() > 256
            || authorizationRef != null && (authorizationRef.length() < 16 || authorizationRef.length() > 128)
            || !isHash(orderSnapshotSha256) || !isHash(requestSha256)) {
            throw new ServiceException("ORDER_DISPOSITION_INVALID: 处置字段、版本或摘要无效", 400);
        }
    }

    private String requestHash(String orderId, Long storeId, String terminalId, Long actorUserId,
                               String shiftId, LocalDate businessDate, String dispositionType,
                               String fromStatus, String effectiveStatus, String reasonCode,
                               String reasonText, String authorizationRef, String snapshotHash) {
        return CanonicalHash.sha256(CanonicalHash.lengthPrefixed(List.of(dispositionType, orderId,
            storeId.toString(), terminalId, actorUserId.toString(), shiftId, businessDate.toString(),
            fromStatus, effectiveStatus, reasonCode, reasonText, authorizationRef == null ? "" : authorizationRef,
            snapshotHash)));
    }

    private boolean isHash(String value) {
        return value != null && value.matches("^[a-f0-9]{64}$");
    }
}
