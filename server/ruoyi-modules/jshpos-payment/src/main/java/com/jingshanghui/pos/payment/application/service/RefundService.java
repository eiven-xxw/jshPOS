package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort.LineSnapshot;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort.OrderPaymentSnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.ApproveRefund;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateRefund;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RefundObservation;
import com.jingshanghui.pos.payment.application.model.PaymentViews.AttemptView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ObservationResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ObservationView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.RefundLineView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.RefundResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.RefundView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ReservedQuantityView;
import com.jingshanghui.pos.payment.domain.PaymentHash;
import com.jingshanghui.pos.payment.domain.PaymentRules;
import com.jingshanghui.pos.payment.domain.PaymentStates.ObservationSource;
import com.jingshanghui.pos.payment.domain.PaymentStates.PaymentStatus;
import com.jingshanghui.pos.payment.domain.PaymentStates.RefundStatus;
import com.jingshanghui.pos.payment.domain.RefundRules;
import com.jingshanghui.pos.payment.domain.RefundRules.RefundLine;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 原成功电子支付的退款申请、占额、审批和观察收敛服务。 */
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final String CREATE_REFUND = "CREATE_ORIGINAL_REFUND";

    private final PaymentMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final PaymentOrderSnapshotPort orderSnapshotPort;
    private final PaymentIdempotencyService idempotency;
    private final PaymentJournalService journal;
    private final UlidGenerator ulids;
    private final Clock clock;

    /** 创建待审批退款；真正占额在审批推进 PROCESSING 时再次于支付锁内校验。 */
    @Transactional
    public RefundResult create(CreateRefund command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        List<RefundLine> lines = validateCreateShape(command);
        PaymentView payment = requirePayment(mapper.lockPayment(principal.tenantId(), command.paymentId()));
        authorizationService.requireStoreAccess(payment.storeId());
        String requestHash = hashCreate(command, lines);
        RefundResult duplicate = idempotency.find(principal.tenantId(), CREATE_REFUND,
            command.idempotencyKey(), requestHash, RefundResult.class);
        if (duplicate != null) {
            return new RefundResult(duplicate.refundId(), duplicate.paymentId(), duplicate.status(),
                duplicate.amountMinor(), duplicate.currency(), duplicate.recordVersion(), true);
        }
        requireRefundablePayment(payment, command.orderId(), command.amountMinor(), command.currency());
        OrderPaymentSnapshot order = orderSnapshotPort.requireSnapshot(command.orderId());
        requireOriginalLines(order, lines, Map.<String, BigDecimal>of());
        AttemptView attempt = mapper.findSucceededAttempt(principal.tenantId(), payment.paymentId());
        if (attempt == null || attempt.providerTransactionNo() == null) {
            throw new ServiceException("REF-PAY-002: 原支付缺少已确认 Provider 流水", 409);
        }
        LocalDateTime at = utc(command.occurredAt());
        mapper.insertRefund(principal.tenantId(), command.refundId(), payment.paymentId(), command.orderId(),
            payment.storeId(), RefundStatus.PENDING_APPROVAL.name(), command.amountMinor(), command.currency(),
            command.reasonCode(), principal.userId(), attempt.providerCode(), command.refundId(), at);
        for (RefundLine line : lines) {
            mapper.insertRefundLine(principal.tenantId(), ulids.next(), command.refundId(), line.orderLineId(),
                line.quantity(), line.amountMinor());
        }
        journal.history(principal.tenantId(), "REFUND", command.refundId(), command.commandId(), null,
            RefundStatus.PENDING_APPROVAL.name(), 1, principal.userId(), command.reasonCode(), at);
        journal.audit(principal.tenantId(), payment.storeId(), "REFUND_CREATED", "REFUND", command.refundId(),
            principal.userId(), null, command.commandId(), null, RefundStatus.PENDING_APPROVAL.name(),
            command.amountMinor(), command.currency(), requestHash, command.reasonCode(), at);
        journal.event(principal.tenantId(), "refund.created.v1", "REFUND", command.refundId(), 1,
            command.commandId(), Map.of("refundId", command.refundId(), "paymentId", payment.paymentId(),
                "orderId", command.orderId(), "amountMinor", command.amountMinor(), "currency", command.currency(),
                "status", RefundStatus.PENDING_APPROVAL.name()), at);
        RefundResult result = new RefundResult(command.refundId(), payment.paymentId(),
            RefundStatus.PENDING_APPROVAL.name(), command.amountMinor(), command.currency(), 1, false);
        idempotency.save(principal.tenantId(), CREATE_REFUND, command.commandId(), command.idempotencyKey(),
            requestHash, command.refundId(), result, at);
        return result;
    }

    /** 独立审批人在支付聚合锁内重新计算全部金额和数量占额。 */
    @Transactional
    public void approve(ApproveRefund command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        PaymentRules.requireUlid(command.commandId(), "commandId");
        PaymentRules.requireUlid(command.refundId(), "refundId");
        requireReason(command.reasonCode());
        if (command.occurredAt() == null) throw new ServiceException("REF-INPUT-001: occurredAt 必填", 409);
        RefundView refund = requireRefund(mapper.lockRefund(principal.tenantId(), command.refundId()));
        authorizationService.requireStoreAccess(refund.storeId());
        if (refund.requesterUserId().equals(principal.userId())) {
            throw new ServiceException("REF-RBAC-001: 退款申请人与审批人必须分离", 409);
        }
        RefundStatus before = RefundStatus.valueOf(refund.status());
        RefundRules.requireTransition(before, RefundStatus.PROCESSING);
        PaymentView payment = requirePayment(mapper.lockPayment(principal.tenantId(), refund.paymentId()));
        requireRefundablePayment(payment, refund.orderId(), refund.amountMinor(), refund.currency());
        List<RefundLineView> persisted = mapper.findRefundLines(principal.tenantId(), refund.refundId());
        List<RefundLine> lines = persisted.stream()
            .map(line -> new RefundLine(line.orderLineId(), line.quantity(), line.amountMinor())).toList();
        OrderPaymentSnapshot order = orderSnapshotPort.requireSnapshot(refund.orderId());
        long reservedAmount = mapper.sumReservedRefundAmount(principal.tenantId(), payment.paymentId());
        RefundRules.requireAmountAvailable(payment.amountMinor(), reservedAmount, refund.amountMinor());
        Map<String, BigDecimal> reserved = new HashMap<>();
        for (ReservedQuantityView value : mapper.findReservedQuantities(principal.tenantId(), payment.paymentId())) {
            reserved.put(value.orderLineId(), value.reservedQuantity());
        }
        requireOriginalLines(order, lines, reserved);
        if (mapper.updateRefundStatus(principal.tenantId(), refund.refundId(), RefundStatus.PROCESSING.name(),
            principal.userId(), null, refund.recordVersion()) != 1) {
            throw new ServiceException("REF-STATE-002: 退款审批并发冲突", 409);
        }
        LocalDateTime at = utc(command.occurredAt());
        String requestHash = PaymentHash.sha256(PaymentHash.canonical(List.of(command.refundId(),
            principal.userId(), command.reasonCode(), command.occurredAt())));
        journal.history(principal.tenantId(), "REFUND", refund.refundId(), command.commandId(), before.name(),
            RefundStatus.PROCESSING.name(), refund.recordVersion() + 1, principal.userId(), command.reasonCode(), at);
        journal.audit(principal.tenantId(), refund.storeId(), "REFUND_APPROVED", "REFUND", refund.refundId(),
            refund.requesterUserId(), principal.userId(), command.commandId(), before.name(),
            RefundStatus.PROCESSING.name(), refund.amountMinor(), refund.currency(), requestHash,
            command.reasonCode(), at);
        journal.event(principal.tenantId(), "refund.status.changed.v1", "REFUND", refund.refundId(),
            refund.recordVersion() + 1, command.commandId(), Map.of("refundId", refund.refundId(),
                "before", before.name(), "after", RefundStatus.PROCESSING.name()), at);
    }

    /** 合并原退款请求的可信观察；成功后同步推进支付的累计退款状态。 */
    @Transactional
    public ObservationResult acceptObservation(RefundObservation observation) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        RefundObservationShape shape = validateObservation(observation);
        ObservationView existing = mapper.findObservation(principal.tenantId(), observation.observationId());
        if (existing != null) {
            if (!"REFUND".equals(existing.aggregateType()) || !observation.refundId().equals(existing.aggregateId())) {
                return conflict(principal, observation, "OBSERVATION_IDENTITY_CONFLICT", existing.payloadSha256(),
                    "相同 observationId 已绑定其他聚合主体", null);
            }
            if (existing.payloadSha256().equals(observation.payloadHash())) {
                RefundView current = requireRefund(mapper.findRefund(principal.tenantId(), existing.aggregateId()));
                authorizationService.requireStoreAccess(current.storeId());
                return new ObservationResult(existing.aggregateId(), current.status(), current.status(), "DUPLICATE", true);
            }
            return conflict(principal, observation, "OBSERVATION_ID_HASH_CONFLICT", existing.payloadSha256(),
                "相同 observationId 对应不同 payload hash", null);
        }
        RefundView refund = requireRefund(mapper.lockRefund(principal.tenantId(), observation.refundId()));
        authorizationService.requireStoreAccess(refund.storeId());
        PaymentView payment = requirePayment(mapper.lockPayment(principal.tenantId(), refund.paymentId()));
        String mismatch = refundObservationMismatch(refund, observation, shape.expectedHash());
        if (mismatch != null) {
            mapper.insertObservation(principal.tenantId(), observation.observationId(), "REFUND", refund.refundId(),
                null, shape.source().name(), shape.observed().name(), observation.providerCode(),
                observation.providerRequestNo(), observation.providerRefundNo(), observation.amountMinor(),
                observation.currency(), observation.payloadHash(), "CONFLICT", utc(observation.observedAt()));
            return conflict(principal, observation, "OBSERVATION_MISMATCH", null, mismatch, refund);
        }
        RefundStatus before = RefundStatus.valueOf(refund.status());
        if (before == RefundStatus.CREATED || before == RefundStatus.PENDING_APPROVAL) {
            return conflict(principal, observation, "REFUND_NOT_DISPATCHABLE", null,
                "退款尚未完成独立审批", refund);
        }
        RefundStatus after = RefundRules.merge(before, shape.observed());
        String outcome = before == after ? "IGNORED" : "APPLIED";
        LocalDateTime at = utc(observation.observedAt());
        mapper.insertObservation(principal.tenantId(), observation.observationId(), "REFUND", refund.refundId(),
            null, shape.source().name(), shape.observed().name(), observation.providerCode(),
            observation.providerRequestNo(), observation.providerRefundNo(), observation.amountMinor(),
            observation.currency(), observation.payloadHash(), outcome, at);
        if (before != after && mapper.updateRefundStatus(principal.tenantId(), refund.refundId(), after.name(), null,
            observation.providerRefundNo(), refund.recordVersion()) != 1) {
            throw new ServiceException("REF-STATE-003: 退款观察并发冲突", 409);
        }
        if (before != after) {
            journal.history(principal.tenantId(), "REFUND", refund.refundId(), observation.observationId(),
                before.name(), after.name(), refund.recordVersion() + 1, principal.userId(), shape.source().name(), at);
            journal.event(principal.tenantId(), "refund.status.changed.v1", "REFUND", refund.refundId(),
                refund.recordVersion() + 1, observation.observationId(), Map.of("refundId", refund.refundId(),
                    "paymentId", refund.paymentId(), "before", before.name(), "after", after.name()), at);
        }
        if (after == RefundStatus.SUCCEEDED && before != RefundStatus.SUCCEEDED) {
            long succeeded = mapper.sumSucceededRefundAmount(principal.tenantId(), payment.paymentId());
            PaymentStatus paymentAfter = PaymentRules.afterSuccessfulRefund(payment.amountMinor(), succeeded);
            if (mapper.updatePaymentRefund(principal.tenantId(), payment.paymentId(), paymentAfter.name(), succeeded,
                payment.recordVersion()) != 1) {
                throw new ServiceException("REF-PAY-003: 支付退款累计并发冲突", 409);
            }
        }
        journal.audit(principal.tenantId(), refund.storeId(), "REFUND_OBSERVATION_" + outcome, "REFUND",
            refund.refundId(), principal.userId(), refund.approverUserId(), observation.observationId(), before.name(),
            after.name(), refund.amountMinor(), refund.currency(), observation.payloadHash(), shape.source().name(), at);
        return new ObservationResult(refund.refundId(), before.name(), after.name(), outcome, false);
    }

    @Transactional(readOnly = true)
    public RefundView find(String refundId) {
        PaymentRules.requireUlid(refundId, "refundId");
        RefundView refund = requireRefund(mapper.findRefund(tenantContext.requireTenantId(), refundId));
        authorizationService.requireStoreAccess(refund.storeId());
        return refund;
    }

    private List<RefundLine> validateCreateShape(CreateRefund command) {
        PaymentRules.requireUlid(command.commandId(), "commandId");
        PaymentRules.requireIdempotencyKey(command.idempotencyKey());
        PaymentRules.requireUlid(command.refundId(), "refundId");
        PaymentRules.requireUlid(command.paymentId(), "paymentId");
        PaymentRules.requireUlid(command.orderId(), "orderId");
        PaymentRules.requireCurrency(command.currency());
        requireReason(command.reasonCode());
        if (command.occurredAt() == null) throw new ServiceException("REF-INPUT-001: occurredAt 必填", 409);
        List<RefundLine> lines = new ArrayList<>();
        try {
            for (var line : command.lines()) {
                lines.add(new RefundLine(line.orderLineId(), new BigDecimal(line.quantity()), line.amountMinor()));
            }
        } catch (RuntimeException exception) {
            throw new ServiceException("REF-LINE-003: 退款数量不是精确十进制", 409);
        }
        RefundRules.validateLines(lines, command.amountMinor());
        return List.copyOf(lines);
    }

    private void requireRefundablePayment(PaymentView payment, String orderId, long amount, String currency) {
        PaymentStatus status = PaymentStatus.valueOf(payment.status());
        if ((status != PaymentStatus.SUCCEEDED && status != PaymentStatus.PARTIALLY_REFUNDED)
            || !payment.orderId().equals(orderId) || !payment.currency().equals(currency) || amount > payment.amountMinor()) {
            throw new ServiceException("REF-PAY-001: 原支付状态、订单、币种或金额不可退款", 409);
        }
    }

    private void requireOriginalLines(OrderPaymentSnapshot order, List<RefundLine> requested,
                                      Map<String, BigDecimal> reserved) {
        Map<String, LineSnapshot> original = new HashMap<>();
        for (LineSnapshot line : order.lines()) original.put(line.lineId(), line);
        for (RefundLine line : requested) {
            LineSnapshot source = original.get(line.orderLineId());
            if (source == null || line.amountMinor() > source.payableAmountMinor()) {
                throw new ServiceException("REF-LINE-004: 退款行不存在或金额超过原成交行", 409);
            }
            RefundRules.requireQuantityAvailable(source.quantity(),
                reserved.getOrDefault(line.orderLineId(), BigDecimal.ZERO), line.quantity());
        }
    }

    private RefundObservationShape validateObservation(RefundObservation observation) {
        PaymentRules.requireUlid(observation.observationId(), "observationId");
        PaymentRules.requireUlid(observation.refundId(), "refundId");
        PaymentRules.requirePositiveAmount(observation.amountMinor(), "amountMinor");
        PaymentRules.requireCurrency(observation.currency());
        PaymentRules.requireProviderCode(observation.providerCode());
        PaymentRules.requireHash(observation.payloadHash());
        requireText(observation.providerRequestNo(), 96, "providerRequestNo");
        if (observation.providerRefundNo() != null) requireText(observation.providerRefundNo(), 96, "providerRefundNo");
        if (observation.observedAt() == null) throw new ServiceException("REF-OBS-001: observedAt 必填", 409);
        ObservationSource source = enumValue(ObservationSource.class, observation.source(), "REF-OBS-002");
        if (source == ObservationSource.FAKE_TEST) {
            throw new ServiceException("REF-OBS-003: FAKE_TEST 观察不得进入正式运行时", 409);
        }
        RefundStatus observed = enumValue(RefundStatus.class, observation.observedStatus(), "REF-OBS-004");
        if (observed == RefundStatus.CREATED || observed == RefundStatus.PENDING_APPROVAL
            || (observed == RefundStatus.SUCCEEDED && observation.providerRefundNo() == null)) {
            throw new ServiceException("REF-OBS-005: 退款观察状态或成功流水不完整", 409);
        }
        String expectedHash = PaymentHash.sha256(PaymentHash.canonical(List.of(observation.observationId(),
            observation.refundId(), source.name(), observed.name(), observation.amountMinor(), observation.currency(),
            observation.providerCode(), observation.providerRequestNo(), String.valueOf(observation.providerRefundNo()),
            observation.observedAt().toString())));
        return new RefundObservationShape(source, observed, expectedHash);
    }

    private String refundObservationMismatch(RefundView refund, RefundObservation observation, String expectedHash) {
        if (!expectedHash.equals(observation.payloadHash())) return "payload hash 与规范退款观察字段不一致";
        if (refund.amountMinor() != observation.amountMinor() || !refund.currency().equals(observation.currency())) {
            return "观察金额或币种与退款不一致";
        }
        if (!refund.providerCode().equals(observation.providerCode())
            || !refund.providerRequestNo().equals(observation.providerRequestNo())) {
            return "观察 Provider 或原退款请求号不一致";
        }
        if (refund.providerRefundNo() != null && observation.providerRefundNo() != null
            && !refund.providerRefundNo().equals(observation.providerRefundNo())) {
            return "已绑定 Provider 退款流水发生冲突";
        }
        return null;
    }

    private ObservationResult conflict(TrustedPrincipal principal, RefundObservation observation, String type,
                                       String existingHash, String reason, RefundView refund) {
        LocalDateTime at = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        mapper.insertDeadLetter(principal.tenantId(), ulids.next(), observation.observationId(), "REFUND",
            observation.refundId(), type, existingHash, observation.payloadHash(), reason, at);
        journal.audit(principal.tenantId(), refund == null ? null : refund.storeId(), "REFUND_OBSERVATION_CONFLICT",
            "REFUND", observation.refundId(), principal.userId(), refund == null ? null : refund.approverUserId(),
            observation.observationId(), refund == null ? null : refund.status(), "CONFLICT",
            refund == null ? null : refund.amountMinor(), refund == null ? null : refund.currency(),
            observation.payloadHash(), type, at);
        return new ObservationResult(observation.refundId(), refund == null ? "UNKNOWN" : refund.status(),
            refund == null ? "UNKNOWN" : refund.status(), "CONFLICT", false);
    }

    private String hashCreate(CreateRefund command, List<RefundLine> lines) {
        List<Object> values = new ArrayList<>();
        values.add(command.refundId()); values.add(command.paymentId()); values.add(command.orderId());
        values.add(command.amountMinor()); values.add(command.currency()); values.add(command.reasonCode());
        values.add(command.occurredAt());
        for (RefundLine line : lines) {
            values.add(line.orderLineId()); values.add(line.quantity().toPlainString()); values.add(line.amountMinor());
        }
        return PaymentHash.sha256(PaymentHash.canonical(values));
    }

    private void requireReason(String value) {
        if (value == null || !value.matches("^[A-Z0-9_]{2,32}$")) {
            throw new ServiceException("REF-INPUT-002: reasonCode 格式非法", 409);
        }
    }

    private void requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new ServiceException("REF-INPUT-003: " + field + " 格式非法", 409);
        }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, String code) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException exception) {
            throw new ServiceException(code + ": 状态枚举非法", 409);
        }
    }

    private PaymentView requirePayment(PaymentView value) {
        if (value == null) throw new ServiceException("PAY-NOT-VISIBLE: 支付不存在或不可见", 404);
        return value;
    }

    private RefundView requireRefund(RefundView value) {
        if (value == null) throw new ServiceException("REF-NOT-VISIBLE: 退款不存在或不可见", 404);
        return value;
    }

    private LocalDateTime utc(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record RefundObservationShape(ObservationSource source, RefundStatus observed, String expectedHash) {
    }
}
