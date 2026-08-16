package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort.OrderPaymentSnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateAttempt;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateIntent;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.PaymentObservation;
import com.jingshanghui.pos.payment.application.model.PaymentViews.AttemptResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.AttemptView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ObservationResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ObservationView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentView;
import com.jingshanghui.pos.payment.domain.PaymentHash;
import com.jingshanghui.pos.payment.domain.PaymentRules;
import com.jingshanghui.pos.payment.domain.PaymentStates.AttemptStatus;
import com.jingshanghui.pos.payment.domain.PaymentStates.ObservationSource;
import com.jingshanghui.pos.payment.domain.PaymentStates.PaymentStatus;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider 无关支付意图、attempt 与观察合并应用服务。
 *
 * <p>本服务只处理本地事务，不持有 HTTP 客户端，也不会产生任何 Provider 网络副作用。</p>
 */
@Service
@RequiredArgsConstructor
public class PaymentCoreService {

    private static final String CREATE_INTENT = "CREATE_PAYMENT_INTENT";
    private static final String CREATE_ATTEMPT = "CREATE_PAYMENT_ATTEMPT";

    private final PaymentMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final PaymentOrderSnapshotPort orderSnapshotPort;
    private final PaymentIdempotencyService idempotency;
    private final PaymentJournalService journal;
    private final UlidGenerator ulids;
    private final Clock clock;

    /** 原子创建支付意图、状态历史、审计、Outbox 和幂等结果。 */
    @Transactional
    public PaymentResult createIntent(CreateIntent command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validateIntentShape(command);
        authorizationService.requireStoreAccess(command.storeId());
        String requestHash = hashIntent(command);
        PaymentResult duplicate = idempotency.find(principal.tenantId(), CREATE_INTENT,
            command.idempotencyKey(), requestHash, PaymentResult.class);
        if (duplicate != null) {
            return new PaymentResult(duplicate.paymentId(), duplicate.status(), duplicate.amountMinor(),
                duplicate.currency(), duplicate.recordVersion(), true);
        }
        OrderPaymentSnapshot order = orderSnapshotPort.requireSnapshot(command.orderId());
        requirePayableOrder(command, order);
        LocalDateTime at = utc(command.occurredAt());
        mapper.insertPayment(principal.tenantId(), command.paymentId(), command.orderId(), command.storeId(),
            command.terminalId(), command.amountMinor(), command.currency(), at);
        journal.history(principal.tenantId(), "PAYMENT", command.paymentId(), command.commandId(), null,
            PaymentStatus.CREATED.name(), 1, principal.userId(), "ORDER_PAYMENT", at);
        journal.audit(principal.tenantId(), command.storeId(), "PAYMENT_INTENT_CREATED", "PAYMENT",
            command.paymentId(), principal.userId(), null, command.commandId(), null, PaymentStatus.CREATED.name(),
            command.amountMinor(), command.currency(), requestHash, "ORDER_PAYMENT", at);
        journal.event(principal.tenantId(), "payment.intent.created.v1", "PAYMENT", command.paymentId(), 1,
            command.commandId(), Map.of("paymentId", command.paymentId(), "orderId", command.orderId(),
                "storeId", command.storeId().toString(), "amountMinor", command.amountMinor(),
                "currency", command.currency(), "status", PaymentStatus.CREATED.name()), at);
        PaymentResult result = new PaymentResult(command.paymentId(), PaymentStatus.CREATED.name(),
            command.amountMinor(), command.currency(), 1, false);
        idempotency.save(principal.tenantId(), CREATE_INTENT, command.commandId(), command.idempotencyKey(),
            requestHash, command.paymentId(), result, at);
        return result;
    }

    /** 创建稳定 attempt 请求事实；UNKNOWN 或成功资金态会在锁内拒绝。 */
    @Transactional
    public AttemptResult createAttempt(CreateAttempt command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validateAttemptShape(command);
        PaymentView payment = requirePayment(mapper.lockPayment(principal.tenantId(), command.paymentId()));
        authorizationService.requireStoreAccess(payment.storeId());
        String requestHash = hashAttempt(command);
        AttemptResult duplicate = idempotency.find(principal.tenantId(), CREATE_ATTEMPT,
            command.idempotencyKey(), requestHash, AttemptResult.class);
        if (duplicate != null) {
            return new AttemptResult(duplicate.attemptId(), duplicate.paymentId(), duplicate.status(),
                duplicate.providerCode(), duplicate.providerRequestNo(), true);
        }
        PaymentStatus current = PaymentStatus.valueOf(payment.status());
        PaymentRules.requireNewAttemptAllowed(current, mapper.countAttempts(principal.tenantId(), payment.paymentId()));
        LocalDateTime at = utc(command.occurredAt());
        mapper.insertAttempt(principal.tenantId(), command.attemptId(), payment.paymentId(), command.providerCode(),
            command.providerRequestNo(), payment.amountMinor(), payment.currency(), at);
        if (mapper.updatePaymentStatus(principal.tenantId(), payment.paymentId(), PaymentStatus.PROCESSING.name(),
            payment.recordVersion()) != 1) {
            throw new ServiceException("PAY-STATE-001: 支付状态并发冲突", 409);
        }
        journal.history(principal.tenantId(), "ATTEMPT", command.attemptId(), command.commandId(), null,
            AttemptStatus.CREATED.name(), 1, principal.userId(), "EXPLICIT_ATTEMPT", at);
        journal.history(principal.tenantId(), "PAYMENT", payment.paymentId(), command.commandId(), current.name(),
            PaymentStatus.PROCESSING.name(), payment.recordVersion() + 1, principal.userId(), "EXPLICIT_ATTEMPT", at);
        journal.audit(principal.tenantId(), payment.storeId(), "PAYMENT_ATTEMPT_CREATED", "PAYMENT",
            payment.paymentId(), principal.userId(), null, command.commandId(), current.name(),
            PaymentStatus.PROCESSING.name(), payment.amountMinor(), payment.currency(), requestHash,
            "EXPLICIT_ATTEMPT", at);
        journal.event(principal.tenantId(), "payment.attempt.created.v1", "PAYMENT", payment.paymentId(),
            payment.recordVersion() + 1, command.commandId(), Map.of("paymentId", payment.paymentId(),
                "attemptId", command.attemptId(), "providerCode", command.providerCode(),
                "providerRequestNo", command.providerRequestNo(), "status", AttemptStatus.CREATED.name()), at);
        AttemptResult result = new AttemptResult(command.attemptId(), payment.paymentId(),
            AttemptStatus.CREATED.name(), command.providerCode(), command.providerRequestNo(), false);
        idempotency.save(principal.tenantId(), CREATE_ATTEMPT, command.commandId(), command.idempotencyKey(),
            requestHash, command.attemptId(), result, at);
        return result;
    }

    @Transactional(readOnly = true)
    public PaymentView find(String paymentId) {
        PaymentRules.requireUlid(paymentId, "paymentId");
        PaymentView payment = requirePayment(mapper.findPayment(tenantContext.requireTenantId(), paymentId));
        authorizationService.requireStoreAccess(payment.storeId());
        return payment;
    }

    /** 合并已由未来适配器验签的标准观察；同 ID 异哈希和主体不匹配进入 dead letter。 */
    @Transactional
    public ObservationResult acceptPayment(PaymentObservation observation) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ObservationShape shape = validateObservation(observation);
        ObservationView existing = mapper.findObservation(principal.tenantId(), observation.observationId());
        if (existing != null) {
            if (!"PAYMENT".equals(existing.aggregateType()) || !observation.paymentId().equals(existing.aggregateId())) {
                return observationConflict(principal, observation.observationId(), "PAYMENT", observation.paymentId(),
                    "OBSERVATION_IDENTITY_CONFLICT", existing.payloadSha256(), observation.payloadHash(),
                    "相同 observationId 已绑定其他聚合主体", null);
            }
            if (existing.payloadSha256().equals(observation.payloadHash())) {
                PaymentView current = requirePayment(mapper.findPayment(principal.tenantId(), existing.aggregateId()));
                authorizationService.requireStoreAccess(current.storeId());
                return new ObservationResult(existing.aggregateId(), current.status(), current.status(), "DUPLICATE", true);
            }
            return observationConflict(principal, observation.observationId(), "PAYMENT", observation.paymentId(),
                "OBSERVATION_ID_HASH_CONFLICT", existing.payloadSha256(), observation.payloadHash(),
                "相同 observationId 对应不同 payload hash", null);
        }
        PaymentView payment = requirePayment(mapper.lockPayment(principal.tenantId(), observation.paymentId()));
        authorizationService.requireStoreAccess(payment.storeId());
        AttemptView attempt = mapper.lockAttempt(principal.tenantId(), observation.attemptId());
        if (attempt == null || !attempt.paymentId().equals(payment.paymentId())) {
            return observationConflict(principal, observation.observationId(), "PAYMENT", observation.paymentId(),
                "ATTEMPT_NOT_VISIBLE", null, observation.payloadHash(), "attempt 不属于当前支付或租户", payment);
        }
        String mismatch = paymentObservationMismatch(payment, attempt, observation, shape.expectedHash());
        if (mismatch != null) {
            mapper.insertObservation(principal.tenantId(), observation.observationId(), "PAYMENT", payment.paymentId(),
                attempt.attemptId(), shape.source().name(), shape.observed().name(), observation.providerCode(),
                observation.providerRequestNo(), observation.providerTransactionNo(), observation.amountMinor(),
                observation.currency(), observation.payloadHash(), "CONFLICT", utc(observation.observedAt()));
            return observationConflict(principal, observation.observationId(), "PAYMENT", payment.paymentId(),
                "OBSERVATION_MISMATCH", null, observation.payloadHash(), mismatch, payment);
        }
        AttemptStatus beforeAttempt = AttemptStatus.valueOf(attempt.status());
        AttemptStatus afterAttempt = PaymentRules.mergeAttempt(beforeAttempt, shape.observed());
        PaymentStatus beforePayment = PaymentStatus.valueOf(payment.status());
        PaymentStatus afterPayment = PaymentRules.merge(beforePayment, shape.observed());
        boolean changed = beforeAttempt != afterAttempt || beforePayment != afterPayment;
        String outcome = changed ? "APPLIED" : "IGNORED";
        LocalDateTime at = utc(observation.observedAt());
        mapper.insertObservation(principal.tenantId(), observation.observationId(), "PAYMENT", payment.paymentId(),
            attempt.attemptId(), shape.source().name(), shape.observed().name(), observation.providerCode(),
            observation.providerRequestNo(), observation.providerTransactionNo(), observation.amountMinor(),
            observation.currency(), observation.payloadHash(), outcome, at);
        if (beforeAttempt != afterAttempt && mapper.updateAttemptStatus(principal.tenantId(), attempt.attemptId(),
            afterAttempt.name(), observation.providerTransactionNo(), attempt.recordVersion()) != 1) {
            throw new ServiceException("PAY-ATTEMPT-003: attempt 并发冲突", 409);
        }
        if (beforePayment != afterPayment && mapper.updatePaymentStatus(principal.tenantId(), payment.paymentId(),
            afterPayment.name(), payment.recordVersion()) != 1) {
            throw new ServiceException("PAY-STATE-001: 支付状态并发冲突", 409);
        }
        if (beforePayment != afterPayment) {
            journal.history(principal.tenantId(), "PAYMENT", payment.paymentId(), observation.observationId(),
                beforePayment.name(), afterPayment.name(), payment.recordVersion() + 1, principal.userId(),
                "PROVIDER_OBSERVATION", at);
            journal.event(principal.tenantId(), "payment.status.changed.v1", "PAYMENT", payment.paymentId(),
                payment.recordVersion() + 1, observation.observationId(), Map.of("paymentId", payment.paymentId(),
                    "attemptId", attempt.attemptId(), "before", beforePayment.name(), "after", afterPayment.name(),
                    "source", shape.source().name()), at);
        }
        journal.audit(principal.tenantId(), payment.storeId(), "PAYMENT_OBSERVATION_" + outcome, "PAYMENT",
            payment.paymentId(), principal.userId(), null, observation.observationId(), beforePayment.name(),
            afterPayment.name(), payment.amountMinor(), payment.currency(), observation.payloadHash(),
            shape.source().name(), at);
        return new ObservationResult(payment.paymentId(), beforePayment.name(), afterPayment.name(), outcome, false);
    }

    private ObservationShape validateObservation(PaymentObservation observation) {
        PaymentRules.requireUlid(observation.observationId(), "observationId");
        PaymentRules.requireUlid(observation.paymentId(), "paymentId");
        PaymentRules.requireUlid(observation.attemptId(), "attemptId");
        PaymentRules.requireProviderCode(observation.providerCode());
        PaymentRules.requireCurrency(observation.currency());
        PaymentRules.requirePositiveAmount(observation.amountMinor(), "amountMinor");
        PaymentRules.requireHash(observation.payloadHash());
        requireText(observation.providerRequestNo(), 96, "providerRequestNo");
        if (observation.providerTransactionNo() != null) requireText(observation.providerTransactionNo(), 96, "providerTransactionNo");
        if (observation.observedAt() == null) throw new ServiceException("PAY-OBS-001: observedAt 必填", 409);
        ObservationSource source = enumValue(ObservationSource.class, observation.source(), "PAY-OBS-002");
        if (source == ObservationSource.FAKE_TEST) {
            throw new ServiceException("PAY-OBS-003: FAKE_TEST 观察不得进入正式运行时", 409);
        }
        AttemptStatus observed = enumValue(AttemptStatus.class, observation.observedStatus(), "PAY-OBS-004");
        if (observed == AttemptStatus.CREATED || (observed == AttemptStatus.SUCCEEDED
            && observation.providerTransactionNo() == null)) {
            throw new ServiceException("PAY-OBS-005: 观察状态或成功渠道流水不完整", 409);
        }
        String expectedHash = PaymentHash.sha256(PaymentHash.canonical(List.of(observation.observationId(),
            observation.paymentId(), observation.attemptId(), source.name(), observed.name(),
            observation.amountMinor(), observation.currency(), observation.providerCode(),
            observation.providerRequestNo(), String.valueOf(observation.providerTransactionNo()),
            observation.observedAt().toString())));
        return new ObservationShape(source, observed, expectedHash);
    }

    private String paymentObservationMismatch(PaymentView payment, AttemptView attempt,
                                              PaymentObservation observation, String expectedHash) {
        if (!expectedHash.equals(observation.payloadHash())) return "payload hash 与规范观察字段不一致";
        if (payment.amountMinor() != observation.amountMinor() || !payment.currency().equals(observation.currency())) {
            return "观察金额或币种与支付意图不一致";
        }
        if (!attempt.providerCode().equals(observation.providerCode())
            || !attempt.providerRequestNo().equals(observation.providerRequestNo())) {
            return "观察 Provider 或原请求号与 attempt 不一致";
        }
        if (attempt.providerTransactionNo() != null && observation.providerTransactionNo() != null
            && !attempt.providerTransactionNo().equals(observation.providerTransactionNo())) {
            return "已绑定 Provider 流水号发生冲突";
        }
        return null;
    }

    private ObservationResult observationConflict(TrustedPrincipal principal, String observationId,
                                                   String aggregateType, String aggregateId, String conflictType,
                                                   String existingHash, String receivedHash, String reason,
                                                   PaymentView payment) {
        LocalDateTime at = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        mapper.insertDeadLetter(principal.tenantId(), ulids.next(), observationId, aggregateType, aggregateId,
            conflictType, existingHash, receivedHash, reason, at);
        journal.audit(principal.tenantId(), payment == null ? null : payment.storeId(),
            "PAYMENT_OBSERVATION_CONFLICT", aggregateType, aggregateId, principal.userId(), null,
            observationId, payment == null ? null : payment.status(), "CONFLICT",
            payment == null ? null : payment.amountMinor(), payment == null ? null : payment.currency(),
            receivedHash, conflictType, at);
        return new ObservationResult(aggregateId, payment == null ? "UNKNOWN" : payment.status(),
            payment == null ? "UNKNOWN" : payment.status(), "CONFLICT", false);
    }

    private void validateIntentShape(CreateIntent command) {
        PaymentRules.requireUlid(command.commandId(), "commandId");
        PaymentRules.requireIdempotencyKey(command.idempotencyKey());
        PaymentRules.requireUlid(command.paymentId(), "paymentId");
        PaymentRules.requireUlid(command.orderId(), "orderId");
        PaymentRules.requireUlid(command.terminalId(), "terminalId");
        if (command.storeId() == null || command.storeId() <= 0 || command.occurredAt() == null) {
            throw new ServiceException("PAY-INPUT-001: 门店或发生时间非法", 409);
        }
        PaymentRules.requirePositiveAmount(command.amountMinor(), "amountMinor");
        PaymentRules.requireCurrency(command.currency());
    }

    private void validateAttemptShape(CreateAttempt command) {
        PaymentRules.requireUlid(command.commandId(), "commandId");
        PaymentRules.requireIdempotencyKey(command.idempotencyKey());
        PaymentRules.requireUlid(command.attemptId(), "attemptId");
        PaymentRules.requireUlid(command.paymentId(), "paymentId");
        PaymentRules.requireProviderCode(command.providerCode());
        requireText(command.providerRequestNo(), 96, "providerRequestNo");
        if (command.occurredAt() == null) throw new ServiceException("PAY-INPUT-002: occurredAt 必填", 409);
    }

    private void requirePayableOrder(CreateIntent command, OrderPaymentSnapshot order) {
        boolean payableState = ("PENDING_PAYMENT".equals(order.status()) || "CONFIRMED".equals(order.status()))
            && "UNPAID".equals(order.paymentStatus());
        if (!payableState || !order.storeId().equals(command.storeId())
            || !order.terminalId().equals(command.terminalId())
            || order.receivableAmountMinor() != command.amountMinor()
            || !order.currency().equals(command.currency())) {
            throw new ServiceException("PAY-ORDER-003: 原单状态、主体或金额不允许电子支付", 409);
        }
    }

    private PaymentView requirePayment(PaymentView payment) {
        if (payment == null) throw new ServiceException("PAY-NOT-VISIBLE: 支付不存在或不可见", 404);
        return payment;
    }

    private String hashIntent(CreateIntent command) {
        return PaymentHash.sha256(PaymentHash.canonical(List.of(command.paymentId(), command.orderId(),
            command.storeId(), command.terminalId(), command.amountMinor(), command.currency(), command.occurredAt())));
    }

    private String hashAttempt(CreateAttempt command) {
        return PaymentHash.sha256(PaymentHash.canonical(List.of(command.attemptId(), command.paymentId(),
            command.providerCode(), command.providerRequestNo(), command.occurredAt())));
    }

    private LocalDateTime utc(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new ServiceException("PAY-INPUT-003: " + field + " 格式非法", 409);
        }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, String code) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException exception) {
            throw new ServiceException(code + ": 状态枚举非法", 409);
        }
    }

    private record ObservationShape(ObservationSource source, AttemptStatus observed, String expectedHash) {
    }
}
