package com.jingshanghui.pos.catalog.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.importing.CatalogImportPreflight;
import com.jingshanghui.pos.catalog.application.importing.CatalogImportRow;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ImportBatchView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ImportErrorView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ImportPreflightView;
import com.jingshanghui.pos.catalog.domain.CatalogRules;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CatalogImportService {

    private final CatalogMapper mapper;
    private final CatalogImportPreflight preflight;
    private final TrustedTenantContext tenantContext;
    private final DomainAuditService auditService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final CatalogOutboxService outboxService;

    @Transactional
    public ImportPreflightView preflight(String idempotencyKey, List<CatalogImportRow> rows) {
        String tenantId = tenantContext.requireTenantId();
        String key = requireKey(idempotencyKey);
        CatalogImportPreflight.Result result = preflight.validate(rows);
        ImportBatchView existing = mapper.findImportByKey(tenantId, key);
        if (existing != null) {
            if (!existing.payloadSha256().equals(result.payloadSha256())) {
                throw new ServiceException("CAT-IMP-004: 幂等键已对应不同摘要", 409);
            }
            return new ImportPreflightView(existing, toViews(result.errors()));
        }
        Long batchId = IdWorker.getId();
        Long previousBatchId = mapper.findCurrentImportBatch(tenantId);
        String state = result.accepted() ? "PRECHECKED" : "REJECTED";
        mapper.insertImportBatch(tenantId, batchId, key, result.payloadSha256(), result.rowCount(),
            result.errorCount(), state, previousBatchId);
        if (result.accepted()) {
            for (CatalogImportRow row : rows) {
                String canonicalJson = canonicalRow(row);
                mapper.insertImportRecord(tenantId, IdWorker.getId(), batchId, row.rowNumber(),
                    CatalogRules.requireCode(row.skuCode(), "CAT-PRD-010"), canonicalJson, sha256(canonicalJson));
            }
        } else {
            for (CatalogImportPreflight.RowError error : result.errors()) {
                mapper.insertImportError(tenantId, IdWorker.getId(), batchId, error.rowNumber(),
                    error.field(), truncate(error.message(), 500));
            }
        }
        ImportBatchView batch = mapper.findImport(tenantId, batchId);
        auditService.append("CATALOG_IMPORT_PREFLIGHTED", "IMPORT_BATCH", batchId, null, batch,
            Map.of("rows", result.rowCount(), "errors", result.errorCount(), "accepted", result.accepted()));
        return new ImportPreflightView(batch, toViews(result.errors()));
    }

    @Transactional
    public ImportBatchView publish(Long batchId) {
        String tenantId = tenantContext.requireTenantId();
        ImportBatchView batch = requireBatch(tenantId, batchId);
        if ("PUBLISHED".equals(batch.state())) {
            return batch;
        }
        if (!"PRECHECKED".equals(batch.state()) || batch.errorCount() != 0) {
            throw new ServiceException("CAT-IMP-005: 只有零错误预检批次可发布", 409);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Long previous = mapper.findCurrentImportBatch(tenantId);
        mapper.activateImportBatch(tenantId, IdWorker.getId(), batchId, previous, now);
        if (mapper.publishImportBatch(tenantId, batchId, now) != 1) {
            throw new ServiceException("CAT-IMP-006: 导入批次并发状态冲突", 409);
        }
        ImportBatchView published = requireBatch(tenantId, batchId);
        auditService.append("CATALOG_IMPORT_PUBLISHED", "IMPORT_BATCH", batchId, batch, published,
            Map.of("previousBatchId", previous == null ? 0L : previous, "payloadSha256", batch.payloadSha256()));
        outboxService.append(tenantId, "product.changed.v1", "IMPORT_BATCH", batchId, 1,
            "{\"changeType\":\"IMPORTED\",\"batchId\":" + batchId + ",\"payloadSha256\":\"" + batch.payloadSha256() + "\"}");
        return published;
    }

    @Transactional
    public ImportBatchView rollback(Long batchId) {
        String tenantId = tenantContext.requireTenantId();
        ImportBatchView batch = requireBatch(tenantId, batchId);
        Long current = mapper.findCurrentImportBatch(tenantId);
        Long previous = mapper.findPreviousImportBatch(tenantId);
        if (!"PUBLISHED".equals(batch.state()) || !batchId.equals(current) || previous == null) {
            throw new ServiceException("CAT-IMP-009: 仅当前且存在前版的发布批次可回退", 409);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (mapper.rollbackImportBinding(tenantId, batchId, previous, now) != 1
            || mapper.markImportRolledBack(tenantId, batchId) != 1) {
            throw new ServiceException("CAT-IMP-010: 导入批次回退并发冲突", 409);
        }
        ImportBatchView after = requireBatch(tenantId, batchId);
        auditService.append("CATALOG_IMPORT_ROLLED_BACK", "IMPORT_BATCH", batchId, batch, after,
            Map.of("restoredBatchId", previous));
        return after;
    }

    private ImportBatchView requireBatch(String tenantId, Long batchId) {
        ImportBatchView batch = mapper.findImport(tenantId, batchId);
        if (batch == null) {
            throw new ServiceException("CAT-IMP-007: 导入批次不存在或不可见", 404);
        }
        return batch;
    }

    private String requireKey(String value) {
        String key = value == null ? "" : value.trim();
        if (!key.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")) {
            throw new ServiceException("CAT-IMP-008: 幂等键格式无效", 400);
        }
        return key;
    }

    private List<ImportErrorView> toViews(List<CatalogImportPreflight.RowError> errors) {
        List<ImportErrorView> result = new ArrayList<>(errors.size());
        for (CatalogImportPreflight.RowError error : errors) {
            result.add(new ImportErrorView(error.rowNumber(), error.field(), error.message()));
        }
        return result;
    }

    private String truncate(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), max));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String canonicalRow(CatalogImportRow row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("CAT-IMP-011: 导入行无法 canonical 序列化", 400);
        }
    }
}
