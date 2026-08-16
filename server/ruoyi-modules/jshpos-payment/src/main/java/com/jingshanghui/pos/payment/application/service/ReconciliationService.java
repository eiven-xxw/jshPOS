package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RunReconciliation;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.StatementEntry;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.TransitionCase;
import com.jingshanghui.pos.payment.application.model.PaymentViews.InternalFactView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ReconciliationCaseView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ReconciliationResult;
import com.jingshanghui.pos.payment.domain.PaymentHash;
import com.jingshanghui.pos.payment.domain.PaymentRules;
import com.jingshanghui.pos.payment.domain.PaymentStates.DifferenceType;
import com.jingshanghui.pos.payment.domain.PaymentStates.ReconciliationStatus;
import com.jingshanghui.pos.payment.domain.ReconciliationRules;
import com.jingshanghui.pos.payment.domain.ReconciliationRules.Fact;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 内部资金事实与受控账单来源的不可变双源对账服务。 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private static final String RUN_RECONCILIATION = "RUN_RECONCILIATION";

    private final PaymentMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final PaymentIdempotencyService idempotency;
    private final PaymentJournalService journal;
    private final UlidGenerator ulids;

    /** 持久化账单来源并生成差异；任何差异都不会直接修改支付或退款事实。 */
    @Transactional
    public ReconciliationResult run(RunReconciliation command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validateRun(command);
        String requestHash = hashRun(command);
        ReconciliationResult duplicate = idempotency.find(principal.tenantId(), RUN_RECONCILIATION,
            command.idempotencyKey(), requestHash, ReconciliationResult.class);
        if (duplicate != null) {
            return new ReconciliationResult(duplicate.runId(), duplicate.statementEntries(),
                duplicate.casesOpened(), true);
        }
        LocalDateTime at = utc(command.occurredAt());
        mapper.insertReconciliationRun(principal.tenantId(), command.runId(), command.providerCode(),
            command.statementDate(), command.entries().size(), principal.userId(), at);
        Map<String, List<StatementEntry>> grouped = new LinkedHashMap<>();
        for (StatementEntry entry : command.entries()) {
            mapper.insertStatementEntry(principal.tenantId(), command.runId(), entry.entryId(),
                command.providerCode(), entry.providerTransactionNo(), entry.businessType(), entry.status(),
                entry.amountMinor(), entry.currency(), utc(entry.occurredAt()), entry.payloadHash());
            grouped.computeIfAbsent(key(entry.providerTransactionNo(), entry.businessType()), ignored -> new ArrayList<>())
                .add(entry);
        }
        int cases = 0;
        Set<String> statementKeys = new HashSet<>();
        Set<String> matchedInternalKeys = new HashSet<>();
        for (Map.Entry<String, List<StatementEntry>> item : grouped.entrySet()) {
            List<StatementEntry> sameReference = item.getValue();
            StatementEntry entry = sameReference.get(0);
            statementKeys.add(item.getKey());
            if (sameReference.size() > 1) {
                cases += openCase(principal.tenantId(), command.runId(), DifferenceType.DUPLICATE_PROVIDER_REF,
                    null, entry.providerTransactionNo(), at);
            }
            List<InternalFactView> candidates = mapper.findInternalFactsByReference(principal.tenantId(),
                command.providerCode(), entry.providerTransactionNo());
            InternalFactView internal = candidates.stream()
                .filter(candidate -> entry.businessType().equals(candidate.businessType())).findFirst()
                .orElseGet(() -> candidates.stream().findFirst().orElse(null));
            if (internal != null) {
                matchedInternalKeys.add(key(internal.reference(), internal.businessType()));
            }
            Fact internalFact = internal == null ? null : fact(internal);
            Fact statementFact = new Fact(entry.providerTransactionNo(), entry.businessType(), entry.status(),
                entry.amountMinor(), entry.currency());
            for (DifferenceType difference : ReconciliationRules.compare(internalFact, statementFact)) {
                cases += openCase(principal.tenantId(), command.runId(), difference,
                    internal == null ? null : internal.reference(), entry.providerTransactionNo(), at);
            }
        }
        LocalDateTime from = command.statementDate().atStartOfDay();
        LocalDateTime to = command.statementDate().plusDays(1).atStartOfDay();
        for (InternalFactView internal : mapper.findInternalFacts(principal.tenantId(), command.providerCode(), from, to)) {
            if (!statementKeys.contains(key(internal.reference(), internal.businessType()))
                && !matchedInternalKeys.contains(key(internal.reference(), internal.businessType()))) {
                cases += openCase(principal.tenantId(), command.runId(), DifferenceType.INTERNAL_ONLY,
                    internal.reference(), null, at);
            }
        }
        if (mapper.completeReconciliationRun(principal.tenantId(), command.runId(), cases) != 1) {
            throw new ServiceException("REC-RUN-002: 对账批次状态并发冲突", 409);
        }
        journal.history(principal.tenantId(), "RECONCILIATION", command.runId(), command.commandId(),
            "PROCESSING", "COMPLETED", 1, principal.userId(), "STATEMENT_MATCH", at);
        journal.audit(principal.tenantId(), null, "RECONCILIATION_COMPLETED", "RECONCILIATION",
            command.runId(), principal.userId(), null, command.commandId(), "PROCESSING", "COMPLETED",
            null, null, requestHash, "STATEMENT_MATCH", at);
        journal.event(principal.tenantId(), "reconciliation.run.completed.v1", "RECONCILIATION",
            command.runId(), 1, command.commandId(), Map.of("runId", command.runId(),
                "providerCode", command.providerCode(), "statementDate", command.statementDate().toString(),
                "entryCount", command.entries().size(), "caseCount", cases), at);
        ReconciliationResult result = new ReconciliationResult(command.runId(), command.entries().size(), cases, false);
        idempotency.save(principal.tenantId(), RUN_RECONCILIATION, command.commandId(), command.idempotencyKey(),
            requestHash, command.runId(), result, at);
        return result;
    }

    /** 按固定状态机处理差异；解决人与审批人必须分离。 */
    @Transactional
    public void transition(TransitionCase command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        PaymentRules.requireUlid(command.commandId(), "commandId");
        PaymentRules.requireUlid(command.caseId(), "caseId");
        requireReason(command.reasonCode(), command.reasonText());
        if (command.occurredAt() == null) throw new ServiceException("REC-INPUT-001: occurredAt 必填", 409);
        ReconciliationStatus target = enumValue(ReconciliationStatus.class, command.targetStatus());
        ReconciliationCaseView value = mapper.lockReconciliationCase(principal.tenantId(), command.caseId());
        if (value == null) throw new ServiceException("REC-NOT-VISIBLE: 对账案例不存在或不可见", 404);
        ReconciliationStatus before = ReconciliationStatus.valueOf(value.status());
        try {
            ReconciliationRules.requireTransition(before, target);
        } catch (IllegalStateException exception) {
            throw new ServiceException(exception.getMessage(), 409);
        }
        Long resolver = target == ReconciliationStatus.RESOLVED ? principal.userId() : null;
        Long approver = target == ReconciliationStatus.APPROVED ? principal.userId() : null;
        if (target == ReconciliationStatus.APPROVED
            && (value.resolverUserId() == null || value.resolverUserId().equals(principal.userId()))) {
            throw new ServiceException("REC-RBAC-001: 差异解决人与审批人必须分离", 409);
        }
        if (mapper.updateReconciliationCase(principal.tenantId(), value.caseId(), target.name(), resolver, approver,
            command.reasonCode(), command.reasonText(), value.recordVersion()) != 1) {
            throw new ServiceException("REC-STATE-002: 对账案例并发冲突", 409);
        }
        LocalDateTime at = utc(command.occurredAt());
        String requestHash = PaymentHash.sha256(PaymentHash.canonical(List.of(command.caseId(), target.name(),
            command.reasonCode(), command.reasonText(), command.occurredAt())));
        journal.history(principal.tenantId(), "RECONCILIATION", value.caseId(), command.commandId(), before.name(),
            target.name(), value.recordVersion() + 1, principal.userId(), command.reasonCode(), at);
        journal.audit(principal.tenantId(), null, "RECONCILIATION_CASE_" + target.name(), "RECONCILIATION",
            value.caseId(), principal.userId(), approver, command.commandId(), before.name(), target.name(),
            null, null, requestHash, command.reasonCode(), at);
        journal.event(principal.tenantId(), target == ReconciliationStatus.CLOSED
                ? "reconciliation.case.closed.v1" : "reconciliation.case.changed.v1",
            "RECONCILIATION", value.caseId(), value.recordVersion() + 1, command.commandId(),
            Map.of("caseId", value.caseId(), "runId", value.runId(), "differenceType", value.differenceType(),
                "before", before.name(), "after", target.name()), at);
    }

    private int openCase(String tenantId, String runId, DifferenceType difference,
                         String internalReference, String providerReference, LocalDateTime at) {
        mapper.insertReconciliationCase(tenantId, ulids.next(), runId, difference.name(),
            internalReference, providerReference, at);
        return 1;
    }

    private void validateRun(RunReconciliation command) {
        PaymentRules.requireUlid(command.commandId(), "commandId");
        PaymentRules.requireIdempotencyKey(command.idempotencyKey());
        PaymentRules.requireUlid(command.runId(), "runId");
        PaymentRules.requireProviderCode(command.providerCode());
        if (command.statementDate() == null || command.occurredAt() == null || command.entries().size() > 10_000) {
            throw new ServiceException("REC-RUN-001: 对账日期、发生时间或批次容量非法", 409);
        }
        for (StatementEntry entry : command.entries()) validateEntry(entry);
    }

    private void validateEntry(StatementEntry entry) {
        PaymentRules.requireUlid(entry.entryId(), "entryId");
        requireText(entry.providerTransactionNo(), 96, "providerTransactionNo");
        if (!Set.of("PAYMENT", "REFUND").contains(entry.businessType())
            || !Set.of("SUCCEEDED", "FAILED", "UNKNOWN").contains(entry.status())
            || entry.amountMinor() < 0 || entry.occurredAt() == null) {
            throw new ServiceException("REC-ENTRY-001: 账单来源行字段非法", 409);
        }
        PaymentRules.requireCurrency(entry.currency());
        PaymentRules.requireHash(entry.payloadHash());
        String expected = PaymentHash.sha256(PaymentHash.canonical(List.of(entry.entryId(),
            entry.providerTransactionNo(), entry.businessType(), entry.status(), entry.amountMinor(),
            entry.currency(), entry.occurredAt())));
        if (!expected.equals(entry.payloadHash())) {
            throw new ServiceException("REC-ENTRY-002: 账单来源行摘要不匹配", 409);
        }
    }

    private String hashRun(RunReconciliation command) {
        List<Object> values = new ArrayList<>();
        values.add(command.runId()); values.add(command.providerCode()); values.add(command.statementDate());
        for (StatementEntry entry : command.entries()) {
            values.add(entry.entryId()); values.add(entry.providerTransactionNo()); values.add(entry.businessType());
            values.add(entry.status()); values.add(entry.amountMinor()); values.add(entry.currency());
            values.add(entry.occurredAt()); values.add(entry.payloadHash());
        }
        return PaymentHash.sha256(PaymentHash.canonical(values));
    }

    private Fact fact(InternalFactView value) {
        return new Fact(value.reference(), value.businessType(), value.status(), value.amountMinor(), value.currency());
    }

    private String key(String reference, String businessType) {
        return businessType + "\u0000" + reference;
    }

    private void requireReason(String code, String text) {
        if (code == null || !code.matches("^[A-Z0-9_]{2,32}$") || text == null || text.isBlank()
            || text.length() > 512) {
            throw new ServiceException("REC-INPUT-002: 差异原因不完整", 409);
        }
    }

    private void requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new ServiceException("REC-INPUT-003: " + field + " 格式非法", 409);
        }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException exception) {
            throw new ServiceException("REC-STATE-003: 状态枚举非法", 409);
        }
    }

    private LocalDateTime utc(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
