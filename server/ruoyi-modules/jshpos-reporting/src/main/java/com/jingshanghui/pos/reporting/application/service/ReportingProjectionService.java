package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.StoreView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.*;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.RebuildView;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.SourceApplyView;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.*;
import com.jingshanghui.pos.reporting.domain.CanonicalReportHash;
import com.jingshanghui.pos.reporting.domain.ReportRules;
import com.jingshanghui.pos.reporting.domain.ReportStates;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 来源事件投影与重建事务边界。业务事实只读，Reporting 只写自己的 Inbox、检查点和投影。
 */
@Service
@RequiredArgsConstructor
public class ReportingProjectionService {
    private final ReportingPersistencePort persistence;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final StoreService storeService;
    private final ReportingDifferenceService differenceService;
    private final DomainAuditService auditService;
    private final Clock clock;

    @Transactional
    public SourceApplyView ingest(SourceEvent rawEvent) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        SourceEvent event = validateAndNormalize(rawEvent);
        InboxRow existing = persistence.findInbox(principal.tenantId(), event.sourceEventId());
        if (existing != null) {
            return duplicateResult(principal.tenantId(), event, existing);
        }
        if (!persistence.insertInboxIfAbsent(principal.tenantId(), event)) {
            InboxRow raced = persistence.findInbox(principal.tenantId(), event.sourceEventId());
            return duplicateResult(principal.tenantId(), event, raced);
        }
        persistence.ensureProjectionRegistry(principal.tenantId(), event.metricFamily(), event.projectionVersion());
        String activeVersion = persistence.activeProjectionVersion(principal.tenantId(), event.metricFamily());
        applyProjection(principal.tenantId(), activeVersion, event);
        persistence.markInboxApplied(principal.tenantId(), event.sourceEventId(), clock.instant());
        CheckpointRow checkpoint = advanceCheckpoint(principal.tenantId(), event);
        persistence.upsertProjectionLineage(principal.tenantId(), activeVersion, event, checkpoint,
            dimensionSha256(event), clock.instant());
        String familyStatus = "INCOMPLETE".equals(checkpoint.status())
            || persistence.hasIncompleteCheckpoint(principal.tenantId(), event.metricFamily())
            ? "INCOMPLETE" : "CURRENT";
        persistence.updateProjectionStatus(principal.tenantId(), activeVersion, event, familyStatus);
        auditService.append("REPORT_SOURCE_APPLIED", "REPORT_SOURCE_EVENT", event.sourceEventId(), null, null,
            Map.of("owner", event.sourceOwner(), "family", event.metricFamily(), "businessDate",
                event.businessDate().toString(), "contentSha256", event.contentSha256(),
                "projectionStatus", familyStatus));
        return new SourceApplyView(event.sourceEventId(), true, familyStatus,
            checkpoint.contiguousSequence(), checkpoint.maximumSeenSequence());
    }

    @Transactional
    public RebuildView rebuild(Rebuild command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        ReportRules.requireUlid(command.rebuildId(), "RPT-G5D-040");
        ReportRules.requireCode(command.projectionVersion(), "RPT-G5D-041");
        ReportRules.requireDateRange(command.fromDate(), command.toDate());
        ReportRules.requireUlid(command.correlationId(), "RPT-G5D-042");
        RebuildRow existing = persistence.findRebuild(principal.tenantId(), command.rebuildId());
        if (existing != null) {
            if (!existing.projectionVersion().equals(command.projectionVersion())
                || !existing.fromDate().equals(command.fromDate()) || !existing.toDate().equals(command.toDate())) {
                throw new ServiceException("RPT-G5D-043: 同重建标识内容不同", 409);
            }
            return new RebuildView(existing.rebuildId(), existing.projectionVersion(), existing.eventCount(),
                existing.projectionDigest(), existing.state());
        }
        if (persistence.hasIncompleteCheckpoint(principal.tenantId(), "SALES")
            || persistence.hasIncompleteCheckpoint(principal.tenantId(), "INVENTORY_COST")) {
            throw new ServiceException("RPT-G5D-062: 来源分区存在缺口，禁止切换重建投影", 409);
        }
        Instant startedAt = clock.instant();
        persistence.insertRebuildIfAbsent(principal.tenantId(), new RebuildRow(command.rebuildId(),
            command.projectionVersion(), command.fromDate(), command.toDate(), "RUNNING", principal.userId(),
            command.correlationId(), 0, null, startedAt));
        persistence.clearProjectionVersion(principal.tenantId(), command.projectionVersion(),
            command.fromDate(), command.toDate());
        List<StoredSourceEvent> events = persistence.listAppliedEvents(principal.tenantId(), command.fromDate(),
            command.toDate()).stream().sorted(Comparator.comparing((StoredSourceEvent stored) ->
                stored.event().businessDate()).thenComparing(stored -> stored.event().sourceOwner())
                .thenComparing(stored -> stored.event().partitionKey())
                .thenComparingLong(stored -> stored.event().sourceSequence())
                .thenComparing(stored -> stored.event().sourceEventId())).toList();
        for (StoredSourceEvent stored : events) {
            SourceEvent event = stored.event();
            CheckpointRow checkpoint = persistence.lockCheckpoint(principal.tenantId(), event.sourceOwner(),
                event.partitionKey());
            if (checkpoint == null || !"CURRENT".equals(checkpoint.status())) {
                throw new ServiceException("RPT-G5D-062: 来源分区存在缺口，禁止切换重建投影", 409);
            }
            applyProjection(principal.tenantId(), command.projectionVersion(), event);
            persistence.upsertProjectionLineage(principal.tenantId(), command.projectionVersion(), event,
                checkpoint, dimensionSha256(event), clock.instant());
            persistence.updateProjectionStatus(principal.tenantId(), command.projectionVersion(), event,
                "CURRENT");
        }
        String digest = persistence.projectionDigest(principal.tenantId(), command.projectionVersion(),
            command.fromDate(), command.toDate());
        if (digest == null || digest.length() != 64) {
            throw new ServiceException("RPT-G5D-044: 重建投影摘要无效", 409);
        }
        persistence.ensureProjectionRegistry(principal.tenantId(), "SALES", command.projectionVersion());
        persistence.ensureProjectionRegistry(principal.tenantId(), "INVENTORY_COST", command.projectionVersion());
        persistence.activateProjectionVersion(principal.tenantId(), "SALES", command.projectionVersion());
        persistence.activateProjectionVersion(principal.tenantId(), "INVENTORY_COST", command.projectionVersion());
        persistence.completeRebuild(principal.tenantId(), command.rebuildId(), "COMPLETED", events.size(), digest,
            clock.instant());
        auditService.append("REPORT_PROJECTION_REBUILT", "REPORT_REBUILD", command.rebuildId(), null, null,
            Map.of("projectionVersion", command.projectionVersion(), "events", events.size(), "digest", digest));
        return new RebuildView(command.rebuildId(), command.projectionVersion(), events.size(), digest, "COMPLETED");
    }

    private SourceEvent validateAndNormalize(SourceEvent event) {
        if (event == null) {
            throw new ServiceException("RPT-G5D-045: 来源事件不能为空", 400);
        }
        ReportRules.requireUlid(event.sourceEventId(), "RPT-G5D-046");
        ReportRules.requireUlid(event.correlationId(), "RPT-G5D-047");
        ReportRules.requireCode(event.sourceOwner(), "RPT-G5D-048");
        ReportRules.requireCode(event.sourceAggregateId(), "RPT-G5D-049");
        ReportRules.requireCode(event.partitionKey(), "RPT-G5D-050");
        ReportRules.requireSha256(event.contentSha256(), "RPT-G5D-051");
        if (event.sourceSequence() <= 0 || !"1.0".equals(event.schemaVersion())
            || !ReportRules.ENGINE_VERSION.equals(event.projectionVersion()) || event.occurredAt() == null
            || event.businessDate() == null || event.orgId() == null || event.orgId() <= 0
            || event.storeId() == null || event.storeId() <= 0) {
            throw new ServiceException("RPT-G5D-052: 来源事件基础字段无效", 400);
        }
        ReportRules.requireOwnerFamily(event.sourceOwner(), event.metricFamily());
        ReportRules.requireCurrency(event.currency());
        authorizationService.requireOrgAccess(event.orgId());
        authorizationService.requireStoreAccess(event.storeId());
        StoreView store = storeService.list().stream().filter(view -> view.storeId().equals(event.storeId()))
            .findFirst().orElseThrow(() -> new ServiceException("RPT-G5D-053: 门店不存在或不可见", 404));
        if (!store.orgUnitId().equals(event.orgId()) || !storeService.businessDate(event.storeId(),
            event.occurredAt()).businessDate().equals(event.businessDate())) {
            throw new ServiceException("RPT-G5D-054: 组织、门店或业务日与权威配置不一致", 409);
        }
        SourceEvent normalized = "SALES".equals(event.metricFamily()) ? normalizeSales(event)
            : normalizeInventory(event);
        String calculated = CanonicalReportHash.sha256(canonical(normalized));
        if (!calculated.equals(normalized.contentSha256())) {
            throw new ServiceException("RPT-G5D-055: 来源内容摘要校验失败", 409);
        }
        return normalized;
    }

    private SourceEvent normalizeSales(SourceEvent event) {
        if (event.sales() == null || event.inventoryCost() != null || event.terminalId() == null
            || event.terminalId().isBlank() || event.cashierId() == null || event.cashierId() <= 0) {
            throw new ServiceException("RPT-G5D-056: 销售指标维度或载荷无效", 400);
        }
        SalesDelta sales = event.sales();
        ReportRules.requireSalesConservation(sales.grossMinor(), sales.discountMinor(), sales.surchargeMinor(),
            sales.receivableMinor());
        return event;
    }

    private SourceEvent normalizeInventory(SourceEvent event) {
        if (event.inventoryCost() == null || event.sales() != null || event.warehouseId() == null
            || event.skuId() == null || event.skuId() <= 0) {
            throw new ServiceException("RPT-G5D-057: 库存成本维度或载荷无效", 400);
        }
        ReportRules.requireUlid(event.warehouseId(), "RPT-G5D-058");
        InventoryCostDelta value = event.inventoryCost();
        InventoryCostDelta normalized = new InventoryCostDelta(exact(value.onHandDelta()),
            exact(value.availableDelta()), exact(value.reservedDelta()), exact(value.ledgerQuantityDelta()),
            exact(value.purchaseQuantityDelta()), exact(value.stocktakeQuantityDelta()),
            exact(value.transferQuantityDelta()), exact(value.inventoryValueDeltaMinor()),
            exact(value.cogsDeltaMinor()), exact(value.purchaseCostDeltaMinor()),
            exact(value.stocktakeCostDeltaMinor()), exact(value.transferCostDeltaMinor()));
        return new SourceEvent(event.sourceEventId(), event.sourceOwner(), event.sourceAggregateId(),
            event.sourceSequence(), event.partitionKey(), event.schemaVersion(), event.projectionVersion(),
            event.contentSha256(), event.occurredAt(), event.businessDate(), event.orgId(), event.storeId(),
            event.terminalId(), event.cashierId(), event.warehouseId(), event.skuId(), event.currency(),
            event.metricFamily(), null, normalized, event.correlationId());
    }

    private BigDecimal exact(BigDecimal value) {
        return ReportRules.exactDecimal(value, "RPT-G5D-059");
    }

    private SourceApplyView duplicateResult(String tenantId, SourceEvent event, InboxRow existing) {
        if (existing == null || !Objects.equals(existing.contentSha256(), event.contentSha256())) {
            differenceService.record("CONTENT_CONFLICT", event.sourceEventId(), event.sourceEventId() + "|"
                + event.contentSha256() + "|" + (existing == null ? "MISSING" : existing.contentSha256()));
            throw new ServiceException("RPT-G5D-060: 同来源事件标识内容不同", 409);
        }
        CheckpointRow checkpoint = persistence.lockCheckpoint(tenantId, event.sourceOwner(), event.partitionKey());
        String status = checkpoint == null ? "INCOMPLETE" : checkpoint.status();
        return new SourceApplyView(event.sourceEventId(), false, status,
            checkpoint == null ? 0 : checkpoint.contiguousSequence(),
            checkpoint == null ? 0 : checkpoint.maximumSeenSequence());
    }

    private void applyProjection(String tenantId, String targetVersion, SourceEvent event) {
        if ("SALES".equals(event.metricFamily())) {
            persistence.upsertSalesProjection(tenantId, targetVersion, event);
        } else {
            persistence.upsertInventoryCostProjection(tenantId, targetVersion, event);
        }
    }

    private CheckpointRow advanceCheckpoint(String tenantId, SourceEvent event) {
        CheckpointRow checkpoint = persistence.lockCheckpoint(tenantId, event.sourceOwner(), event.partitionKey());
        if (checkpoint == null) {
            long contiguous = event.sourceSequence() == 1 ? 1 : 0;
            long maximum = event.sourceSequence();
            while (persistence.existsAppliedSequence(tenantId, event.sourceOwner(), event.partitionKey(),
                contiguous + 1)) {
                contiguous++;
            }
            String status = ReportStates.projectionStatus(contiguous, maximum);
            persistence.insertCheckpoint(tenantId, event.sourceOwner(), event.partitionKey(), contiguous, maximum,
                status);
            if ("INCOMPLETE".equals(status)) {
                differenceService.record("SEQUENCE_GAP", event.sourceEventId(), event.sourceOwner() + "|"
                    + event.partitionKey() + "|" + contiguous + "|" + maximum);
            }
            return new CheckpointRow(event.sourceOwner(), event.partitionKey(), contiguous, maximum, status, 0);
        }
        long contiguous = checkpoint.contiguousSequence();
        long maximum = Math.max(checkpoint.maximumSeenSequence(), event.sourceSequence());
        while (persistence.existsAppliedSequence(tenantId, event.sourceOwner(), event.partitionKey(), contiguous + 1)) {
            contiguous++;
        }
        String status = ReportStates.projectionStatus(contiguous, maximum);
        if (persistence.updateCheckpoint(tenantId, event.sourceOwner(), event.partitionKey(), contiguous, maximum,
            status, checkpoint.version()) != 1) {
            throw new ServiceException("RPT-G5D-061: 来源检查点并发冲突", 409);
        }
        if ("INCOMPLETE".equals(status)) {
            differenceService.record("SEQUENCE_GAP", event.sourceEventId(), event.sourceOwner() + "|"
                + event.partitionKey() + "|" + contiguous + "|" + maximum);
        }
        return new CheckpointRow(event.sourceOwner(), event.partitionKey(), contiguous, maximum, status,
            checkpoint.version() + 1);
    }

    /** 生成供来源 Owner 和契约测试共同使用的稳定摘要输入。 */
    public static String canonical(SourceEvent event) {
        return String.join("|", event.sourceEventId(), event.sourceOwner(), event.sourceAggregateId(),
            String.valueOf(event.sourceSequence()), event.partitionKey(), event.schemaVersion(),
            event.projectionVersion(), event.occurredAt().toString(), event.businessDate().toString(),
            String.valueOf(event.orgId()), String.valueOf(event.storeId()), Objects.toString(event.terminalId(), ""),
            Objects.toString(event.cashierId(), ""), Objects.toString(event.warehouseId(), ""),
            Objects.toString(event.skuId(), ""), event.currency(), event.metricFamily(),
            event.sales() == null ? "" : salesCanonical(event.sales()),
            event.inventoryCost() == null ? "" : inventoryCanonical(event.inventoryCost()), event.correlationId());
    }

    private static String salesCanonical(SalesDelta value) {
        return value.orderCount() + "," + value.cancelledOrderCount() + "," + value.returnCount() + ","
            + value.grossMinor() + "," + value.discountMinor() + "," + value.surchargeMinor() + ","
            + value.receivableMinor() + "," + value.refundMinor() + "," + value.cashReceivedMinor() + ","
            + value.cashRefundedMinor() + "," + value.shiftDifferenceMinor() + ","
            + value.promotionSnapshotCount();
    }

    private static String inventoryCanonical(InventoryCostDelta value) {
        return value.onHandDelta().toPlainString() + "," + value.availableDelta().toPlainString() + ","
            + value.reservedDelta().toPlainString() + "," + value.ledgerQuantityDelta().toPlainString() + ","
            + value.purchaseQuantityDelta().toPlainString() + "," + value.stocktakeQuantityDelta().toPlainString()
            + "," + value.transferQuantityDelta().toPlainString() + "," + value.inventoryValueDeltaMinor().toPlainString()
            + "," + value.cogsDeltaMinor().toPlainString() + "," + value.purchaseCostDeltaMinor().toPlainString()
            + "," + value.stocktakeCostDeltaMinor().toPlainString() + "," + value.transferCostDeltaMinor().toPlainString();
    }

    private String dimensionSha256(SourceEvent event) {
        String dimension = "SALES".equals(event.metricFamily())
            ? String.join("|", "SALES", event.businessDate().toString(), String.valueOf(event.orgId()),
                String.valueOf(event.storeId()), event.terminalId(), String.valueOf(event.cashierId()), event.currency())
            : String.join("|", "INVENTORY_COST", event.businessDate().toString(), String.valueOf(event.orgId()),
                String.valueOf(event.storeId()), event.warehouseId(), String.valueOf(event.skuId()), event.currency());
        return CanonicalReportHash.sha256(dimension);
    }
}
