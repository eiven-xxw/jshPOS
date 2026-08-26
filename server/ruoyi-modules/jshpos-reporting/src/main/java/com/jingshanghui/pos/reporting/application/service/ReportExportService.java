package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.*;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.*;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.ReconciliationView;
import com.jingshanghui.pos.reporting.application.port.PaymentReconciliationPersistencePort;
import com.jingshanghui.pos.reporting.application.port.ReportArtifactStore;
import com.jingshanghui.pos.reporting.application.port.ReportDownloadTokenProtector;
import com.jingshanghui.pos.reporting.application.port.ReportingBatchReadPort;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.application.port.SalesPageCursorCodec;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.ArtifactRow;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.ExportRow;
import com.jingshanghui.pos.reporting.domain.CanonicalReportHash;
import com.jingshanghui.pos.reporting.domain.ReportRules;
import com.jingshanghui.pos.reporting.domain.ReportStates;
import com.jingshanghui.pos.reporting.domain.SalesReportReadIdentity;
import com.jingshanghui.pos.reporting.infrastructure.export.ReportCsvEncoder;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 安全导出申请、独立审批、生成和单次下载事务边界。 */
@Service
@RequiredArgsConstructor
public class ReportExportService {
    private static final Duration ARTIFACT_RETENTION = Duration.ofHours(24);
    private final ReportingPersistencePort persistence;
    private final ReportingBatchReadPort batchReadPort;
    private final PaymentReconciliationPersistencePort paymentReconciliationPersistence;
    private final ReportArtifactStore artifactStore;
    private final ReportDownloadTokenProtector tokenProtector;
    private final SalesPageCursorCodec cursorCodec;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final ReportingDifferenceService differenceService;
    private final DomainAuditService auditService;
    private final Clock clock;
    private final ReportCsvEncoder csvEncoder;

    @Transactional
    public ExportView request(ExportRequest command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReportRules.requireUlid(command.exportId(), "RPT-G5D-070");
        ReportRules.requireUlid(command.correlationId(), "RPT-G5D-071");
        ReportRules.requireDateRange(command.fromDate(), command.toDate());
        if (command.storeIds().isEmpty() || command.storeIds().size() > 50) {
            throw new ServiceException("RPT-G5D-072: 导出门店范围必须为 1 至 50 个", 400);
        }
        command.storeIds().forEach(authorizationService::requireStoreAccess);
        Set<String> fields = ReportRules.requireExportFields(command.reportType(), command.fields());
        List<Long> stores = command.storeIds().stream().sorted().toList();
        String activeVersion = "PAYMENT_RECONCILIATION".equals(command.reportType()) ? null
            : persistence.activeProjectionVersion(principal.tenantId(),
                "SALES_DAILY".equals(command.reportType()) ? "SALES" : "INVENTORY_COST");
        long counted = "PAYMENT_RECONCILIATION".equals(command.reportType())
            ? paymentReconciliationPersistence.count(principal.tenantId(), command.fromDate(), command.toDate(), stores)
            : activeVersion == null ? 0 : count(command, principal.tenantId(), activeVersion, stores);
        if (counted > ReportRules.MAX_EXPORT_ROWS) {
            throw new ServiceException("RPT-G5D-073: 导出预计行数超过 100000", 413);
        }
        int estimatedRows = Math.toIntExact(counted);
        boolean approvalRequired = ReportRules.requiresApproval(command.reportType(), estimatedRows, fields);
        String storeCsv = joinLongs(stores);
        String fieldCsv = String.join(",", fields.stream().sorted().toList());
        String requestHash = CanonicalReportHash.sha256(command.reportType() + "|" + command.fromDate() + "|"
            + command.toDate() + "|" + storeCsv + "|" + fieldCsv);
        ExportRow existing = persistence.findExport(principal.tenantId(), command.exportId());
        if (existing != null) {
            if (!existing.requestSha256().equals(requestHash)) {
                throw new ServiceException("RPT-G5D-074: 同导出标识内容不同", 409);
            }
            return toView(existing);
        }
        ExportRow row = new ExportRow(command.exportId(), requestHash, command.reportType(), command.fromDate(),
            command.toDate(), storeCsv, fieldCsv, "REQUESTED", approvalRequired, principal.userId(), null,
            estimatedRows, command.correlationId(), null, null, 0, clock.instant());
        if (!persistence.insertExportIfAbsent(principal.tenantId(), row)) {
            throw new ServiceException("RPT-G5D-075: 导出申请并发冲突", 409);
        }
        auditService.append("REPORT_EXPORT_REQUESTED", "REPORT_EXPORT", command.exportId(), null, null,
            Map.of("reportType", command.reportType(), "stores", stores.size(), "fields", fields.size(),
                "estimatedRows", estimatedRows, "approvalRequired", approvalRequired));
        return toView(row);
    }

    @Transactional(readOnly = true)
    public ExportView get(String exportId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ExportRow row = requireExport(principal.tenantId(), exportId);
        parseLongs(row.storeIdsCsv()).forEach(authorizationService::requireStoreAccess);
        return toView(row);
    }

    @Transactional
    public ExportView approve(ExportApproval command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        ExportRow row = requireExport(principal.tenantId(), command.exportId());
        if (!row.approvalRequired()) {
            throw new ServiceException("RPT-G5D-076: 该导出无需审批", 409);
        }
        if (principal.userId().equals(row.requestedBy())) {
            throw new ServiceException("RPT-G5D-077: 申请人与审批人必须分离", 409);
        }
        String target = command.approved() ? "APPROVED" : "REJECTED";
        ReportStates.transitionExport(row.state(), target, true);
        String reasonHash = CanonicalReportHash.sha256(Objects.toString(command.reason(), ""));
        if (persistence.transitionExport(principal.tenantId(), row.exportId(), row.state(), target,
            principal.userId(), reasonHash, command.expectedVersion(), clock.instant()) != 1) {
            throw new ServiceException("RPT-G5D-078: 导出审批版本或状态冲突", 409);
        }
        auditService.append("REPORT_EXPORT_" + target, "REPORT_EXPORT", row.exportId(),
            Map.of("state", row.state()), Map.of("state", target), Map.of("reasonSha256", reasonHash));
        return toView(requireExport(principal.tenantId(), row.exportId()));
    }

    @Transactional
    public ExportView generate(ExportGenerate command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ExportRow row = requireExport(principal.tenantId(), command.exportId());
        List<Long> stores = parseLongs(row.storeIdsCsv());
        stores.forEach(authorizationService::requireStoreAccess);
        String target = ReportStates.transitionExport(row.state(), "GENERATING", row.approvalRequired());
        if (persistence.transitionExport(principal.tenantId(), row.exportId(), row.state(), target, row.approvedBy(),
            null, command.expectedVersion(), clock.instant()) != 1) {
            throw new ServiceException("RPT-G5D-079: 导出生成版本或状态冲突", 409);
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ARTIFACT_RETENTION);
        ReportArtifactStore.StoredArtifact stored = "SALES_DAILY".equals(row.reportType())
            ? writeSalesArtifact(row, principal.tenantId(), stores, now)
            : writeLegacyArtifact(row, principal.tenantId(), stores);
        try {
            persistence.attachArtifact(principal.tenantId(), new ArtifactRow(row.exportId(), stored.objectKey(),
                stored.sha256(), stored.sizeBytes(), "text/csv;charset=UTF-8", now, expiresAt,
                null, null, null, null));
        } catch (RuntimeException exception) {
            artifactStore.delete(stored.objectKey());
            throw exception;
        }
        auditService.append("REPORT_EXPORT_READY", "REPORT_EXPORT", row.exportId(), null, null,
            Map.of("artifactSha256", stored.sha256(), "sizeBytes", stored.sizeBytes(),
                "expiresAt", expiresAt.toString()));
        return toView(requireExport(principal.tenantId(), row.exportId()));
    }

    @Transactional
    public DownloadTokenView issueDownloadToken(String exportId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ExportRow row = requireExport(principal.tenantId(), exportId);
        ArtifactRow artifact = persistence.findArtifact(principal.tenantId(), exportId);
        Instant now = clock.instant();
        if (!"READY".equals(row.state()) || artifact == null || !artifact.expiresAt().isAfter(now)) {
            throw new ServiceException("RPT-G5D-080: 导出制品未就绪或已过期", 409);
        }
        parseLongs(row.storeIdsCsv()).forEach(authorizationService::requireStoreAccess);
        ReportDownloadTokenProtector.TokenIssue token = tokenProtector.issue();
        Instant tokenExpires = now.plus(Duration.ofMinutes(ReportRules.DOWNLOAD_TTL_MINUTES));
        if (persistence.issueDownloadToken(principal.tenantId(), exportId, token.sha256(), principal.userId(),
            tokenExpires, row.version()) != 1) {
            throw new ServiceException("RPT-G5D-081: 下载令牌并发冲突", 409);
        }
        auditService.append("REPORT_DOWNLOAD_TOKEN_ISSUED", "REPORT_EXPORT", exportId, null, null,
            Map.of("userId", principal.userId(), "expiresAt", tokenExpires.toString()));
        return new DownloadTokenView(exportId, token.plaintext(), tokenExpires);
    }

    @Transactional
    public DownloadArtifact download(String exportId, String plaintextToken) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ExportRow row = requireExport(principal.tenantId(), exportId);
        parseLongs(row.storeIdsCsv()).forEach(authorizationService::requireStoreAccess);
        ArtifactRow artifact = persistence.findArtifact(principal.tenantId(), exportId);
        if (artifact == null) {
            throw new ServiceException("RPT-G5D-082: 导出制品不存在", 404);
        }
        byte[] content = artifactStore.get(artifact.objectKey());
        String actual = CanonicalReportHash.sha256(content);
        if (!actual.equals(artifact.artifactSha256())) {
            differenceService.record("ARTIFACT_DIGEST_MISMATCH", exportId, exportId + "|" + actual);
            throw new ServiceException("RPT-G5D-083: 导出制品摘要不一致", 409);
        }
        String tokenHash = tokenProtector.hash(plaintextToken);
        if (persistence.consumeDownloadToken(principal.tenantId(), exportId, tokenHash, principal.userId(),
            clock.instant()) != 1) {
            throw new ServiceException("RPT-G5D-084: 下载令牌无效、过期或已使用", 403);
        }
        auditService.append("REPORT_EXPORT_DOWNLOADED", "REPORT_EXPORT", exportId, null, null,
            Map.of("userId", principal.userId(), "artifactSha256", actual));
        return new DownloadArtifact("jshpos-report-" + exportId + ".csv", artifact.contentType(), content);
    }

    private long count(ExportRequest command, String tenantId, String version, List<Long> stores) {
        return "SALES_DAILY".equals(command.reportType())
            ? persistence.countSales(tenantId, version, command.fromDate(), command.toDate(), stores)
            : persistence.countInventoryCost(tenantId, version, command.fromDate(), command.toDate(), stores);
    }

    private ReportArtifactStore.StoredArtifact writeSalesArtifact(ExportRow row, String tenantId,
                                                                    List<Long> stores, Instant generatedAt) {
        String namespace = "reporting/" + tenantId + "/" + row.exportId();
        Set<String> fields = new TreeSet<>(List.of(row.fieldsCsv().split(",")));
        return artifactStore.writeResumable(namespace, row.requestSha256(), (output, resumeCursor, checkpoint) -> {
            var text = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            String projectionVersion;
            ReportingBatchReadPort.SalesKey after;
            if (resumeCursor == null) {
                projectionVersion = persistence.activeProjectionVersion(tenantId, "SALES");
                csvEncoder.writeSalesHeader(text, tenantId, row.exportId(), fields, generatedAt);
                text.flush();
                if (projectionVersion == null) return;
                checkpoint.saveCheckpoint(cursorCodec.encode(new SalesPageCursorCodec.CursorEnvelope(tenantId,
                    row.requestSha256(), projectionVersion, null)));
                after = null;
            } else {
                SalesPageCursorCodec.CursorEnvelope envelope = cursorCodec.decodeAndVerify(resumeCursor, tenantId,
                    row.requestSha256(), null);
                projectionVersion = envelope.projectionVersion();
                after = envelope.after();
            }
            String filterSha256 = SalesReportReadIdentity.filterSha256(tenantId, projectionVersion, row.fromDate(),
                row.toDate(), stores, null, null);
            while (true) {
                List<SalesDailyView> rows = batchReadPort.readSales(new ReportingBatchReadPort.SalesBatchRequest(
                    tenantId, projectionVersion, row.fromDate(), row.toDate(), stores, null, null, after,
                    ReportingBatchReadPort.MAX_EXPORT_CHUNK_ROWS, filterSha256));
                if (rows.isEmpty()) break;
                csvEncoder.writeSalesRows(text, fields, rows);
                text.flush();
                after = ReportingBatchReadPort.SalesKey.from(rows.get(rows.size() - 1));
                checkpoint.saveCheckpoint(cursorCodec.encode(new SalesPageCursorCodec.CursorEnvelope(tenantId,
                    row.requestSha256(), projectionVersion, after)));
                if (rows.size() < ReportingBatchReadPort.MAX_EXPORT_CHUNK_ROWS) break;
            }
        });
    }

    private ReportArtifactStore.StoredArtifact writeLegacyArtifact(ExportRow row, String tenantId,
                                                                     List<Long> stores) {
        byte[] content = encodeLegacy(row, tenantId, stores);
        String sha256 = CanonicalReportHash.sha256(content);
        String objectKey = "reporting/" + tenantId + "/" + row.exportId() + "/" + sha256 + ".csv";
        artifactStore.put(objectKey, content);
        return new ReportArtifactStore.StoredArtifact(objectKey, sha256, content.length);
    }

    private byte[] encodeLegacy(ExportRow row, String tenantId, List<Long> stores) {
        Set<String> fields = new TreeSet<>(List.of(row.fieldsCsv().split(",")));
        Instant now = clock.instant();
        if ("PAYMENT_RECONCILIATION".equals(row.reportType())) {
            List<ReconciliationView> rows = stores.stream().flatMap(storeId ->
                paymentReconciliationPersistence.query(tenantId, row.fromDate(), row.toDate(), storeId,
                    null, null).stream()).toList();
            return csvEncoder.paymentReconciliation(tenantId, row.exportId(), fields, rows, now);
        }
        String version = persistence.activeProjectionVersion(tenantId, "INVENTORY_COST");
        List<InventoryCostDailyView> rows = stores.stream().flatMap(storeId -> persistence.queryInventoryCost(
            tenantId, version, row.fromDate(), row.toDate(), storeId, null, null).stream()).toList();
        return csvEncoder.inventoryCost(tenantId, row.exportId(), fields, rows, now);
    }

    private ExportRow requireExport(String tenantId, String exportId) {
        ReportRules.requireUlid(exportId, "RPT-G5D-085");
        ExportRow row = persistence.findExport(tenantId, exportId);
        if (row == null) {
            throw new ServiceException("RPT-G5D-086: 导出申请不存在或不可见", 404);
        }
        return row;
    }

    private ExportView toView(ExportRow row) {
        return new ExportView(row.exportId(), row.reportType(), row.fromDate(), row.toDate(),
            new TreeSet<>(parseLongs(row.storeIdsCsv())), new TreeSet<>(List.of(row.fieldsCsv().split(","))),
            row.state(), row.approvalRequired(), row.requestedBy(), row.approvedBy(), row.estimatedRows(),
            row.artifactSha256(), row.expiresAt(), row.version());
    }

    private String joinLongs(List<Long> values) {
        return String.join(",", values.stream().map(String::valueOf).toList());
    }

    private List<Long> parseLongs(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(Long::valueOf).sorted().toList();
    }
}
