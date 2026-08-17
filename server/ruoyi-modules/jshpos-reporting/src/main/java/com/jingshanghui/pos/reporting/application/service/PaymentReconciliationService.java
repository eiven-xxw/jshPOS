package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.StoreView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationCommands.*;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.*;
import com.jingshanghui.pos.reporting.application.port.PaymentReconciliationPersistencePort;
import com.jingshanghui.pos.reporting.application.port.PaymentReconciliationPersistencePort.*;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.RebuildRow;
import com.jingshanghui.pos.reporting.domain.CanonicalReportHash;
import com.jingshanghui.pos.reporting.domain.PaymentReconciliationRules;
import com.jingshanghui.pos.reporting.domain.ReportRules;
import com.jingshanghui.pos.reporting.infrastructure.id.ReportingIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * RPT-002 Provider 无关事实、内部合成账单、确定性匹配、人工处理与重建事务边界。
 * 该服务没有 Provider SDK、HTTP 客户端、回调端点或渠道账单下载能力。
 */
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {
    private static final String REBUILD_VERSION = "g5d-rpt2-v1";
    private final PaymentReconciliationPersistencePort persistence;
    private final ReportingPersistencePort reportingPersistence;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final StoreService storeService;
    private final DomainAuditService auditService;
    private final ReportingIdGenerator idGenerator;
    private final Clock clock;

    @Transactional
    public IngestView ingestFact(PaymentFact raw) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        PaymentFact fact = validateFact(raw);
        FactRow existing = persistence.findFactByEvent(principal.tenantId(), fact.sourceEventId());
        if (existing != null) return duplicateFact(principal.tenantId(), fact, existing);
        FactRow keyConflict = persistence.findFactByKey(principal.tenantId(), fact.reconciliationKey());
        if (keyConflict != null) throw conflict("RPT-G5D-220", "同匹配键已绑定其他内部事实");
        ensureCompatibleWithBill(principal.tenantId(), fact);
        if (!persistence.insertFact(principal.tenantId(), fact, clock.instant())) {
            FactRow raced = persistence.findFactByEvent(principal.tenantId(), fact.sourceEventId());
            return duplicateFact(principal.tenantId(), fact, raced);
        }
        ReconciliationView view = recompute(principal.tenantId(), fact.reconciliationKey(), true, false);
        auditService.append("REPORT_PAYMENT_FACT_INGESTED", "PAYMENT_RECONCILIATION", view.reconciliationId(),
            null, null, Map.of("factType", fact.factType(), "sourceEventId", fact.sourceEventId(),
                "contentSha256", fact.contentSha256(), "differenceType", view.differenceType()));
        return new IngestView(fact.sourceEventId(), view.reconciliationId(), true,
            view.differenceType(), view.handlingState());
    }

    @Transactional
    public IngestView ingestSyntheticBill(SyntheticBillEntry raw) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        SyntheticBillEntry bill = validateBill(raw);
        BillRow existing = persistence.findBillByEntry(principal.tenantId(), bill.billEntryId());
        if (existing != null) return duplicateBill(principal.tenantId(), bill, existing);
        BillRow keyConflict = persistence.findBillByKey(principal.tenantId(), bill.reconciliationKey());
        if (keyConflict != null) throw conflict("RPT-G5D-221", "同匹配键已绑定其他合成账单条目");
        ensureCompatibleWithFact(principal.tenantId(), bill);
        if (!persistence.insertBill(principal.tenantId(), bill, principal.userId(), clock.instant())) {
            BillRow raced = persistence.findBillByEntry(principal.tenantId(), bill.billEntryId());
            return duplicateBill(principal.tenantId(), bill, raced);
        }
        ReconciliationView view = recompute(principal.tenantId(), bill.reconciliationKey(), true, false);
        auditService.append("REPORT_SYNTHETIC_BILL_INGESTED", "PAYMENT_RECONCILIATION", view.reconciliationId(),
            null, null, Map.of("synthetic", true, "externalEvidence", 0, "billEntryId", bill.billEntryId(),
                "contentSha256", bill.contentSha256(), "differenceType", view.differenceType()));
        return new IngestView(bill.billEntryId(), view.reconciliationId(), true,
            view.differenceType(), view.handlingState());
    }

    @Transactional(readOnly = true)
    public List<ReconciliationView> query(Query command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReportRules.requireDateRange(command.fromDate(), command.toDate());
        authorizationService.requireStoreAccess(command.storeId());
        String difference = command.differenceType() == null ? null
            : PaymentReconciliationRules.requireDifference(command.differenceType());
        String handling = command.handlingState() == null ? null
            : PaymentReconciliationRules.requireHandling(command.handlingState());
        return persistence.query(principal.tenantId(), command.fromDate(), command.toDate(), command.storeId(),
            difference, handling);
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit(String reconciliationId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReportRules.requireUlid(reconciliationId, "RPT-G5D-222");
        ReconciliationRow row = persistence.findReconciliation(principal.tenantId(), reconciliationId);
        if (row == null) throw notFound();
        authorizationService.requireStoreAccess(row.storeId());
        return persistence.listAudit(principal.tenantId(), reconciliationId);
    }

    @Transactional
    public ReconciliationView transition(Transition command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        ReportRules.requireUlid(command.reconciliationId(), "RPT-G5D-223");
        ReportRules.requireUlid(command.correlationId(), "RPT-G5D-224");
        ReconciliationRow current = persistence.lockReconciliation(principal.tenantId(), command.reconciliationId());
        if (current == null) throw notFound();
        authorizationService.requireStoreAccess(current.storeId());
        String target = PaymentReconciliationRules.transition(current.handlingState(), command.toState());
        Instant now = clock.instant();
        ReconciliationRow changed = copyWithHandling(current, target, principal.userId(), now);
        if (persistence.updateReconciliation(principal.tenantId(), changed, command.expectedVersion()) != 1) {
            throw conflict("RPT-G5D-225", "对账处理状态或版本冲突");
        }
        String reasonHash = CanonicalReportHash.sha256(Objects.toString(command.reason(), ""));
        persistence.insertAudit(principal.tenantId(), new AuditRow(idGenerator.next(), current.reconciliationId(),
            "MANUAL_TRANSITION", current.differenceType(), current.differenceType(), current.handlingState(), target,
            principal.userId(), reasonHash, command.correlationId(), now));
        auditService.append("REPORT_RECONCILIATION_TRANSITIONED", "PAYMENT_RECONCILIATION",
            current.reconciliationId(), Map.of("state", current.handlingState()), Map.of("state", target),
            Map.of("reasonSha256", reasonHash, "differenceType", current.differenceType()));
        return toView(changed, current.version() + 1);
    }

    @Transactional
    public RebuildView rebuild(Rebuild command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        ReportRules.requireUlid(command.rebuildId(), "RPT-G5D-226");
        ReportRules.requireUlid(command.correlationId(), "RPT-G5D-227");
        ReportRules.requireDateRange(command.fromDate(), command.toDate());
        RebuildRow existing = reportingPersistence.findRebuild(principal.tenantId(), command.rebuildId());
        if (existing != null) {
            if (!REBUILD_VERSION.equals(existing.projectionVersion()) || !command.fromDate().equals(existing.fromDate())
                || !command.toDate().equals(existing.toDate())) {
                throw conflict("RPT-G5D-228", "同重建标识内容不同");
            }
            return new RebuildView(existing.rebuildId(), existing.eventCount(), existing.projectionDigest(),
                existing.state());
        }
        Instant now = clock.instant();
        boolean acquired = reportingPersistence.insertRebuildIfAbsent(principal.tenantId(), new RebuildRow(command.rebuildId(),
            REBUILD_VERSION, command.fromDate(), command.toDate(), "RUNNING", principal.userId(),
            command.correlationId(), 0, null, now));
        if (!acquired) {
            RebuildRow raced = reportingPersistence.findRebuild(principal.tenantId(), command.rebuildId());
            if (raced == null || !REBUILD_VERSION.equals(raced.projectionVersion())
                || !command.fromDate().equals(raced.fromDate()) || !command.toDate().equals(raced.toDate())) {
                throw conflict("RPT-G5D-228", "同重建标识内容不同或并发状态不可见");
            }
            return new RebuildView(raced.rebuildId(), raced.eventCount(), raced.projectionDigest(), raced.state());
        }
        List<String> keys = persistence.listKeys(principal.tenantId(), command.fromDate(), command.toDate());
        persistence.deleteProjection(principal.tenantId(), command.fromDate(), command.toDate());
        keys.forEach(key -> recompute(principal.tenantId(), key, false, true));
        String digest = projectionDigest(principal.tenantId(), command.fromDate(), command.toDate());
        reportingPersistence.completeRebuild(principal.tenantId(), command.rebuildId(), "COMPLETED", keys.size(),
            digest, clock.instant());
        auditService.append("REPORT_PAYMENT_RECONCILIATION_REBUILT", "REPORT_REBUILD", command.rebuildId(),
            null, null, Map.of("keys", keys.size(), "digest", digest, "externalEvidence", 0));
        return new RebuildView(command.rebuildId(), keys.size(), digest, "COMPLETED");
    }

    private PaymentFact validateFact(PaymentFact fact) {
        if (fact == null) throw bad("RPT-G5D-229", "支付退款事实不能为空");
        ReportRules.requireUlid(fact.sourceEventId(), "RPT-G5D-230");
        ReportRules.requireUlid(fact.reconciliationKey(), "RPT-G5D-231");
        ReportRules.requireUlid(fact.orderId(), "RPT-G5D-232");
        ReportRules.requireUlid(fact.correlationId(), "RPT-G5D-233");
        ReportRules.requireCode(fact.partitionKey(), "RPT-G5D-234");
        ReportRules.requireSha256(fact.contentSha256(), "RPT-G5D-235");
        String type = PaymentReconciliationRules.requireFactType(fact.factType());
        if (!type.equals(fact.sourceOwner()) || fact.sourceSequence() <= 0 || !"1.0".equals(fact.schemaVersion())
            || fact.occurredAt() == null || fact.businessDate() == null || fact.orgId() == null || fact.orgId() <= 0
            || fact.storeId() == null || fact.storeId() <= 0 || fact.terminalId() == null
            || fact.terminalId().isBlank() || fact.terminalId().length() > 64) {
            throw bad("RPT-G5D-236", "支付退款事实基础字段无效");
        }
        PaymentReconciliationRules.requireAmount(fact.amountMinor());
        ReportRules.requireCurrency(fact.currency());
        PaymentReconciliationRules.requireLifecycleStatus(fact.lifecycleStatus());
        validateStore(fact.orgId(), fact.storeId(), fact.occurredAt(), fact.businessDate());
        if (!CanonicalReportHash.sha256(canonical(fact)).equals(fact.contentSha256())) {
            throw conflict("RPT-G5D-237", "支付退款事实摘要不一致");
        }
        return fact;
    }

    private SyntheticBillEntry validateBill(SyntheticBillEntry bill) {
        if (bill == null) throw bad("RPT-G5D-238", "内部合成账单不能为空");
        ReportRules.requireUlid(bill.billEntryId(), "RPT-G5D-239");
        ReportRules.requireUlid(bill.batchId(), "RPT-G5D-240");
        ReportRules.requireUlid(bill.reconciliationKey(), "RPT-G5D-241");
        ReportRules.requireUlid(bill.correlationId(), "RPT-G5D-242");
        ReportRules.requireSha256(bill.contentSha256(), "RPT-G5D-243");
        PaymentReconciliationRules.requireFactType(bill.factType());
        if (!"INTERNAL_SYNTHETIC".equals(bill.sourceType()) || !bill.synthetic()
            || !"1.0".equals(bill.schemaVersion()) || bill.businessDate() == null || bill.orgId() == null
            || bill.orgId() <= 0 || bill.storeId() == null || bill.storeId() <= 0 || bill.terminalId() == null
            || bill.terminalId().isBlank() || bill.terminalId().length() > 64) {
            throw bad("RPT-G5D-244", "账单必须显式为内部合成且字段有效");
        }
        PaymentReconciliationRules.requireAmount(bill.amountMinor());
        ReportRules.requireCurrency(bill.currency());
        PaymentReconciliationRules.requireLifecycleStatus(bill.lifecycleStatus());
        authorizationService.requireOrgAccess(bill.orgId());
        authorizationService.requireStoreAccess(bill.storeId());
        StoreView store = findStore(bill.storeId());
        if (!store.orgUnitId().equals(bill.orgId())) throw conflict("RPT-G5D-245", "合成账单组织门店不一致");
        if (!CanonicalReportHash.sha256(canonical(bill)).equals(bill.contentSha256())) {
            throw conflict("RPT-G5D-246", "内部合成账单摘要不一致");
        }
        return bill;
    }

    private void validateStore(Long orgId, Long storeId, Instant occurredAt, java.time.LocalDate businessDate) {
        authorizationService.requireOrgAccess(orgId);
        authorizationService.requireStoreAccess(storeId);
        StoreView store = findStore(storeId);
        if (!store.orgUnitId().equals(orgId)
            || !storeService.businessDate(storeId, occurredAt).businessDate().equals(businessDate)) {
            throw conflict("RPT-G5D-247", "来源事实组织、门店或业务日不一致");
        }
    }

    private StoreView findStore(Long storeId) {
        return storeService.list().stream().filter(value -> value.storeId().equals(storeId)).findFirst()
            .orElseThrow(() -> new ServiceException("RPT-G5D-248: 门店不存在或不可见", 404));
    }

    private IngestView duplicateFact(String tenantId, PaymentFact fact, FactRow existing) {
        if (existing == null || !existing.contentSha256().equals(fact.contentSha256())) {
            throw conflict("RPT-G5D-249", "同内部事实标识内容不同");
        }
        ReconciliationView view = requireProjection(tenantId, fact.reconciliationKey());
        return new IngestView(fact.sourceEventId(), view.reconciliationId(), false,
            view.differenceType(), view.handlingState());
    }

    private IngestView duplicateBill(String tenantId, SyntheticBillEntry bill, BillRow existing) {
        if (existing == null || !existing.contentSha256().equals(bill.contentSha256())) {
            throw conflict("RPT-G5D-250", "同合成账单条目标识内容不同");
        }
        ReconciliationView view = requireProjection(tenantId, bill.reconciliationKey());
        return new IngestView(bill.billEntryId(), view.reconciliationId(), false,
            view.differenceType(), view.handlingState());
    }

    private void ensureCompatibleWithBill(String tenantId, PaymentFact fact) {
        BillRow bill = persistence.findBillByKey(tenantId, fact.reconciliationKey());
        if (bill != null && (!bill.factType().equals(fact.factType()) || !bill.orgId().equals(fact.orgId())
            || !bill.storeId().equals(fact.storeId()) || !bill.terminalId().equals(fact.terminalId()))) {
            throw conflict("RPT-G5D-251", "匹配键跨类型、组织、门店或终端冲突");
        }
    }

    private void ensureCompatibleWithFact(String tenantId, SyntheticBillEntry bill) {
        FactRow fact = persistence.findFactByKey(tenantId, bill.reconciliationKey());
        if (fact != null && (!fact.factType().equals(bill.factType()) || !fact.orgId().equals(bill.orgId())
            || !fact.storeId().equals(bill.storeId()) || !fact.terminalId().equals(bill.terminalId()))) {
            throw conflict("RPT-G5D-251", "匹配键跨类型、组织、门店或终端冲突");
        }
    }

    private ReconciliationView recompute(String tenantId, String key, boolean recordAudit, boolean restoreManual) {
        FactRow fact = persistence.findFactByKey(tenantId, key);
        BillRow bill = persistence.findBillByKey(tenantId, key);
        if (fact == null && bill == null) throw notFound();
        ReconciliationRow current = persistence.lockReconciliation(tenantId, key);
        String difference = PaymentReconciliationRules.classify(fact != null, bill != null,
            fact == null ? null : fact.currency(), bill == null ? null : bill.currency(),
            fact == null ? null : fact.amountMinor(), bill == null ? null : bill.amountMinor(),
            fact == null ? null : fact.lifecycleStatus(), bill == null ? null : bill.lifecycleStatus(),
            fact == null ? null : fact.businessDate(), bill == null ? null : bill.businessDate());
        String handling = "MATCHED".equals(difference) ? "MATCHED" : "OPEN";
        Long handler = null;
        if (!"MATCHED".equals(difference) && current != null && difference.equals(current.differenceType())
            && Set.of("ASSIGNED", "RESOLVED", "IGNORED").contains(current.handlingState())) {
            handling = current.handlingState(); handler = current.handlerId();
        } else if (!"MATCHED".equals(difference) && restoreManual) {
            ManualState manual = persistence.latestManualState(tenantId, key);
            if (manual != null && Set.of("ASSIGNED", "RESOLVED", "IGNORED").contains(manual.handlingState())) {
                handling = manual.handlingState(); handler = manual.handlerId();
            }
        }
        Instant now = clock.instant();
        boolean sameDifference = current != null && difference.equals(current.differenceType());
        ReconciliationRow row = new ReconciliationRow(key, key, fact != null ? fact.factType() : bill.factType(),
            fact == null ? null : fact.sourceEventId(), bill == null ? null : bill.billEntryId(),
            fact != null ? fact.businessDate() : bill.businessDate(), fact != null ? fact.orgId() : bill.orgId(),
            fact != null ? fact.storeId() : bill.storeId(), fact != null ? fact.terminalId() : bill.terminalId(),
            fact != null ? fact.currency() : bill.currency(), fact == null ? null : fact.amountMinor(),
            bill == null ? null : bill.amountMinor(), fact == null ? null : fact.lifecycleStatus(),
            bill == null ? null : bill.lifecycleStatus(), fact == null ? null : fact.businessDate(),
            bill == null ? null : bill.businessDate(), difference, handling, handler,
            fact == null ? null : fact.contentSha256(), bill == null ? null : bill.contentSha256(),
            sameDifference ? current.detectedAt() : now, now, current == null ? 0 : current.version());
        if (current == null) persistence.insertReconciliation(tenantId, row);
        else if (persistence.updateReconciliation(tenantId, row, current.version()) != 1) {
            throw conflict("RPT-G5D-252", "对账投影并发冲突");
        }
        if (recordAudit && (current == null || !difference.equals(current.differenceType())
            || !handling.equals(current.handlingState()))) {
            String correlation = fact != null ? fact.correlationId() : bill.correlationId();
            String reason = CanonicalReportHash.sha256(key + "|" + difference + "|"
                + Objects.toString(row.sourceContentSha256(), "") + "|" + Objects.toString(row.billContentSha256(), ""));
            persistence.insertAudit(tenantId, new AuditRow(idGenerator.next(), key, "SYSTEM_CLASSIFIED",
                current == null ? null : current.differenceType(), difference,
                current == null ? null : current.handlingState(), handling, 0L, reason, correlation, now));
        }
        return toView(row, current == null ? 0 : current.version() + 1);
    }

    private ReconciliationView requireProjection(String tenantId, String key) {
        ReconciliationRow row = persistence.findReconciliation(tenantId, key);
        if (row == null) return recompute(tenantId, key, false, false);
        return toView(row, row.version());
    }

    private ReconciliationRow copyWithHandling(ReconciliationRow row, String handling, Long handler, Instant now) {
        return new ReconciliationRow(row.reconciliationId(), row.reconciliationKey(), row.factType(),
            row.sourceEventId(), row.billEntryId(), row.businessDate(), row.orgId(), row.storeId(), row.terminalId(),
            row.currency(), row.internalAmountMinor(), row.billAmountMinor(), row.internalStatus(), row.billStatus(),
            row.internalBusinessDate(), row.billBusinessDate(), row.differenceType(), handling, handler,
            row.sourceContentSha256(), row.billContentSha256(), row.detectedAt(), now, row.version());
    }

    private ReconciliationView toView(ReconciliationRow row, int version) {
        return new ReconciliationView(row.reconciliationId(), row.reconciliationKey(), row.factType(),
            row.sourceEventId(), row.billEntryId(), row.businessDate(), row.orgId(), row.storeId(), row.terminalId(),
            row.currency(), row.internalAmountMinor(), row.billAmountMinor(), row.internalStatus(), row.billStatus(),
            row.internalBusinessDate(), row.billBusinessDate(), row.differenceType(), row.handlingState(),
            row.handlerId(), row.detectedAt(), row.updatedAt(), version);
    }

    private String projectionDigest(String tenantId, java.time.LocalDate from, java.time.LocalDate to) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ReconciliationView row : persistence.listForDigest(tenantId, from, to)) {
                String canonical = String.join("|", row.reconciliationId(), row.factType(), row.businessDate().toString(),
                    String.valueOf(row.storeId()), row.currency(), Objects.toString(row.internalAmountMinor(), ""),
                    Objects.toString(row.billAmountMinor(), ""), Objects.toString(row.internalStatus(), ""),
                    Objects.toString(row.billStatus(), ""), row.differenceType(), row.handlingState(),
                    Objects.toString(row.handlerId(), ""));
                digest.update(canonical.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    public static String canonical(PaymentFact value) {
        return String.join("|", value.sourceEventId(), value.sourceOwner(), String.valueOf(value.sourceSequence()),
            value.partitionKey(), value.schemaVersion(), value.occurredAt().toString(), value.businessDate().toString(),
            String.valueOf(value.orgId()), String.valueOf(value.storeId()), value.terminalId(), value.factType(),
            value.reconciliationKey(), value.orderId(), String.valueOf(value.amountMinor()), value.currency(),
            value.lifecycleStatus(), value.correlationId());
    }

    public static String canonical(SyntheticBillEntry value) {
        return String.join("|", value.billEntryId(), value.batchId(), value.sourceType(),
            String.valueOf(value.synthetic()), value.schemaVersion(), value.businessDate().toString(),
            String.valueOf(value.orgId()), String.valueOf(value.storeId()), value.terminalId(), value.factType(),
            value.reconciliationKey(), String.valueOf(value.amountMinor()), value.currency(),
            value.lifecycleStatus(), value.correlationId());
    }

    private ServiceException bad(String code, String message) {
        return new ServiceException(code + ": " + message, 400);
    }
    private ServiceException conflict(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }
    private ServiceException notFound() {
        return new ServiceException("RPT-G5D-253: 对账条目不存在或不可见", 404);
    }
}
