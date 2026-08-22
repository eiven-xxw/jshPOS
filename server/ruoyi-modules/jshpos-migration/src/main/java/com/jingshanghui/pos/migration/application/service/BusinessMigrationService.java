package com.jingshanghui.pos.migration.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.BusinessMigrationCatalogPort;
import com.jingshanghui.pos.catalog.application.port.BusinessMigrationCatalogPort.ProductMigrationCommand;
import com.jingshanghui.pos.catalog.application.port.BusinessMigrationCatalogPort.ProductMigrationResult;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.ApplyResult;
import com.jingshanghui.pos.inventory.application.port.BusinessMigrationInventoryPort;
import com.jingshanghui.pos.inventory.application.port.BusinessMigrationInventoryPort.OpeningInventoryCommand;
import com.jingshanghui.pos.member.application.port.BusinessMigrationMemberPort;
import com.jingshanghui.pos.member.application.port.BusinessMigrationMemberPort.MemberMigrationCommand;
import com.jingshanghui.pos.member.application.port.BusinessMigrationMemberPort.MemberMigrationResult;
import com.jingshanghui.pos.migration.application.model.MigrationModels.*;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.*;
import com.jingshanghui.pos.migration.application.port.MigrationStagingCipher;
import com.jingshanghui.pos.migration.application.port.MigrationStagingCipher.SealedValue;
import com.jingshanghui.pos.migration.domain.MigrationRowNormalizer;
import com.jingshanghui.pos.migration.domain.MigrationRowNormalizer.NormalizedRow;
import com.jingshanghui.pos.migration.domain.MigrationRowNormalizer.PreflightResult;
import com.jingshanghui.pos.migration.domain.MigrationRules;
import com.jingshanghui.pos.migration.domain.MigrationStates;
import com.jingshanghui.pos.migration.domain.MigrationStates.BatchState;
import com.jingshanghui.pos.migration.domain.MigrationStates.DataType;
import com.jingshanghui.pos.migration.infrastructure.file.MigrationFileInspector;
import com.jingshanghui.pos.migration.infrastructure.file.MigrationFileInspector.InspectedTable;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.procurement.application.port.BusinessMigrationSupplierPort;
import com.jingshanghui.pos.procurement.application.port.BusinessMigrationSupplierPort.SupplierMigrationCommand;
import com.jingshanghui.pos.procurement.application.port.BusinessMigrationSupplierPort.SupplierMigrationResult;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.HexFormat;

/**
 * 开业资料迁移的批次、预检、双人审批、Saga、对账、激活与清理应用服务。
 *
 * <p>Owner 调用不包在跨模块大事务中；每行使用冻结命令和 Owner 幂等，随后独立记录检查点。</p>
 */
@Service
@RequiredArgsConstructor
public class BusinessMigrationService {
    private static final String MAPPING_VERSION = "1.0";
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final BusinessMigrationPersistencePort persistence;
    private final MigrationFileInspector fileInspector;
    private final MigrationRowNormalizer normalizer;
    private final MigrationStagingCipher cipher;
    private final BusinessMigrationCatalogPort catalog;
    private final BusinessMigrationSupplierPort suppliers;
    private final BusinessMigrationMemberPort members;
    private final BusinessMigrationInventoryPort inventory;
    private final UlidGenerator ids;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    @Transactional
    public BatchView create(CreateBatch command) {
        TrustedPrincipal principal = principalAdmin();
        Set<String> types = normalizedTypes(command.dataTypes());
        requireKey(command.idempotencyKey(), "idempotencyKey"); requireCorrelation(command.correlationId());
        CanonicalJson.Result request = canonical(Map.of("dataTypes", new TreeSet<>(types),
            "idempotencyKey", command.idempotencyKey()));
        BatchRecord replay = persistence.findBatchByIdempotency(principal.tenantId(), command.idempotencyKey());
        if (replay != null) {
            if (!replay.requestSha256().equals(request.sha256())) throw idem();
            return view(replay);
        }
        String batchId = ids.next(); LocalDateTime at = now();
        persistence.insertBatch(new BatchWrite(batchId, principal.tenantId(), json(types), BatchState.UPLOADED.name(),
            command.idempotencyKey(), request.sha256(), command.correlationId(), principal.userId(), at));
        BatchRecord created = requireBatch(principal.tenantId(), batchId);
        appendCreatedFacts(created, principal, at);
        return view(created);
    }

    @Transactional
    public UploadResult upload(UploadFile command) {
        TrustedPrincipal principal = principalAdmin();
        BatchRecord batch = requireBatch(principal.tenantId(), command.batchId());
        DataType type = dataType(command.dataType());
        if (!requested(batch).contains(type.name())) throw new ServiceException("DMT-MAPPING-004: 资料类型不在批次范围", 409);
        if (!MAPPING_VERSION.equals(command.mappingVersion())) throw new ServiceException("DMT-MAPPING-005: 映射版本不兼容", 409);
        requireCorrelation(command.correlationId());
        FileRecord registered = persistence.listFiles(principal.tenantId(), batch.batchId()).stream()
            .filter(file -> file.dataType().equals(type.name())).findFirst().orElse(null);
        if (registered != null) {
            return replayUpload(batch, command, registered);
        }
        if (!Set.of(BatchState.UPLOADED.name(), BatchState.PREFLIGHTING.name()).contains(batch.state())) {
            throw state("只有 UPLOADED/PREFLIGHTING 可登记批次文件");
        }
        if (BatchState.UPLOADED.name().equals(batch.state())) {
            batch = transition(batch, BatchState.PREFLIGHTING, principal, command.correlationId());
        }
        String fileId = ids.next(); LocalDateTime at = now();
        InspectedTable table;
        try {
            table = fileInspector.inspect(command.originalFilename(), command.charset(),
                command.content(), command.declaredSha256());
        } catch (ServiceException exception) {
            return rejectFilePreflight(batch, command, principal, type, fileId, at, exception);
        }
        List<NormalizedRow> rows;
        try {
            PreflightResult preflight = normalizer.preflight(type, table.headers(), table.rows());
            if (!preflight.errors().isEmpty()) {
                persistence.insertFile(fileWrite(command, principal, table, fileId, 0,
                    preflight.errors().size(), "PREFLIGHT_FAILED", at));
                for (var issue : preflight.errors()) {
                    persistence.insertPreflightError(new PreflightErrorWrite(ids.next(), principal.tenantId(),
                        batch.batchId(), fileId, type.name(), issue.rowNumber(), issue.fieldName(), issue.errorCode(),
                        issue.errorCode() + ": " + issue.maskedMessage(), at));
                }
                BatchRecord failed = transition(batch, BatchState.PREFLIGHT_FAILED, principal, command.correlationId());
                FileRecord file = persistence.listFiles(principal.tenantId(), batch.batchId()).stream()
                    .filter(value -> value.fileId().equals(fileId)).findFirst().orElseThrow();
                return new UploadResult(view(failed), fileView(file), 0, preflight.errors().size());
            }
            rows = preflight.rows();
        } catch (ServiceException exception) {
            String code = code(exception.getMessage());
            persistence.insertFile(fileWrite(command, principal, table, fileId, 0, 1, "PREFLIGHT_FAILED", at));
            persistence.insertPreflightError(new PreflightErrorWrite(ids.next(), principal.tenantId(), batch.batchId(),
                fileId, type.name(), 0, null, code, code + ": 预检失败，原始值已隐藏", at));
            BatchRecord failed = transition(batch, BatchState.PREFLIGHT_FAILED, principal, command.correlationId());
            List<FileRecord> failedFiles = persistence.listFiles(principal.tenantId(), batch.batchId());
            return new UploadResult(view(failed), fileView(failedFiles.get(failedFiles.size() - 1)), 0, 1);
        }
        persistence.insertFile(fileWrite(command, principal, table, fileId, rows.size(), 0, "PREFLIGHT_PASSED", at));
        for (NormalizedRow row : rows) {
            String aad = aad(principal.tenantId(), batch.batchId(), row.rowId(), type.name());
            SealedValue sealed = cipher.seal(aad, row.canonicalJson());
            persistence.insertStagingRow(new StagingWrite(row.rowId(), principal.tenantId(), batch.batchId(), fileId,
                type.name(), row.rowNumber(), row.rowSha256(), sealed.cipherText(), sealed.keyVersion(),
                sealed.contentHmac(), at.plusDays(30), at));
        }
        BatchRecord current = requireBatch(principal.tenantId(), batch.batchId());
        if (uploadedTypes(principal.tenantId(), batch.batchId()).containsAll(requested(current))) {
            current = transition(current, BatchState.READY, principal, command.correlationId());
        }
        FileRecord file = persistence.listFiles(principal.tenantId(), batch.batchId()).stream()
            .filter(value -> value.fileId().equals(fileId)).findFirst().orElseThrow();
        return new UploadResult(view(current), fileView(file), rows.size(), 0);
    }

    @Transactional
    public BatchDetail approve(BatchCommand command) {
        TrustedPrincipal principal = principalAdmin();
        BatchRecord batch = requireBatch(principal.tenantId(), command.batchId());
        requireKey(command.idempotencyKey(), "idempotencyKey"); requireCorrelation(command.correlationId());
        String reason = MigrationRules.text(command.reason(), 256, "reason");
        String reasonSha256 = MigrationRules.digest(reason);
        ApprovalRecord replay = persistence.findApprovalByIdempotency(principal.tenantId(), batch.batchId(),
            command.idempotencyKey());
        if (replay != null) {
            if (!principal.userId().equals(replay.approverUserId()) || !reasonSha256.equals(replay.reasonSha256())) {
                throw idem();
            }
            return detail(batch);
        }
        if (!BatchState.READY.name().equals(batch.state())) throw state("只有 READY 可审批");
        if (persistence.hasApproval(principal.tenantId(), batch.batchId(), principal.userId())) {
            throw new ServiceException("DMT-APPROVAL-001: 同一人员不得重复审批", 409);
        }
        persistence.insertApproval(new ApprovalWrite(ids.next(), principal.tenantId(), batch.batchId(),
            principal.userId(), reasonSha256, command.idempotencyKey(), command.correlationId(), now()));
        if (persistence.countApprovals(principal.tenantId(), batch.batchId()) >= 2) {
            batch = transition(batch, BatchState.APPROVED, principal, command.correlationId());
        }
        return detail(batch);
    }

    /** 沿原批次和原行推进，不对 UNKNOWN 或失败行生成替代命令。 */
    public BatchDetail resume(BatchCommand command) {
        TrustedPrincipal principal = principalAdmin(); requireKey(command.idempotencyKey(), "idempotencyKey");
        requireCorrelation(command.correlationId());
        BatchRecord batch = requireBatch(principal.tenantId(), command.batchId());
        if (Set.of(BatchState.IMPORTED.name(), BatchState.RECONCILING.name(), BatchState.RECONCILED.name(),
            BatchState.ACTIVATION_PENDING.name(), BatchState.ACTIVATED.name(), BatchState.CLEANED.name())
            .contains(batch.state())) return detail(batch);
        if (!Set.of(BatchState.APPROVED.name(), BatchState.FAILED.name(),
            BatchState.COMPENSATION_REQUIRED.name(), BatchState.IMPORTING.name()).contains(batch.state())) {
            throw state("当前状态不允许推进导入");
        }
        if (!BatchState.IMPORTING.name().equals(batch.state())) {
            batch = tx(() -> transition(requireBatch(principal.tenantId(), command.batchId()),
                BatchState.IMPORTING, principal, command.correlationId()));
        }
        String activeBatchId = batch.batchId();
        boolean hadApplied = persistence.countAppliedCheckpoints(principal.tenantId(), activeBatchId) > 0;
        for (StagingRecord row : persistence.listStagingRows(principal.tenantId(), activeBatchId)) {
            CheckpointRecord checkpoint = persistence.findCheckpoint(principal.tenantId(), activeBatchId, row.rowId());
            if (checkpoint != null) {
                if (!checkpoint.requestSha256().equals(row.rowSha256()) || !"APPLIED".equals(checkpoint.state())) {
                    throw new ServiceException("DMT-OWNER-001: 检查点与冻结行不一致", 409);
                }
                continue;
            }
            try {
                OwnerResult result = applyOwner(row, command.correlationId());
                tx(() -> { persistence.insertCheckpoint(new CheckpointWrite(ids.next(), principal.tenantId(),
                    activeBatchId, row.rowId(), result.owner(), row.dataType(), result.commandId(),
                    row.rowSha256(), result.resultSha256(), "APPLIED", command.correlationId(), now())); return null; });
                hadApplied = true;
            } catch (RuntimeException exception) {
                BatchState target = hadApplied ? BatchState.COMPENSATION_REQUIRED : BatchState.FAILED;
                BatchRecord finalBatch = batch;
                tx(() -> transition(requireBatch(principal.tenantId(), finalBatch.batchId()), target,
                    principal, command.correlationId()));
                throw exception;
            }
        }
        BatchRecord finalBatch = batch;
        BatchRecord imported = tx(() -> transition(requireBatch(principal.tenantId(), finalBatch.batchId()),
            BatchState.IMPORTED, principal, command.correlationId()));
        return detail(imported);
    }

    @Transactional
    public ReconciliationResult reconcile(BatchCommand command) {
        TrustedPrincipal principal = principalAdmin(); requireKey(command.idempotencyKey(), "idempotencyKey");
        requireCorrelation(command.correlationId());
        BatchRecord batch = requireBatch(principal.tenantId(), command.batchId());
        ReconciliationRecord previous = persistence.latestReconciliation(principal.tenantId(), batch.batchId());
        if (previous != null && Set.of(BatchState.RECONCILED.name(), BatchState.ACTIVATION_PENDING.name(),
            BatchState.ACTIVATED.name(), BatchState.CLEANED.name(), BatchState.COMPENSATION_REQUIRED.name())
            .contains(batch.state())) {
            return reconciliation(previous);
        }
        if (!BatchState.IMPORTED.name().equals(batch.state())) throw state("只有 IMPORTED 可对账");
        batch = transition(batch, BatchState.RECONCILING, principal, command.correlationId());
        int expected = persistence.countStagingRows(principal.tenantId(), batch.batchId());
        int applied = persistence.countAppliedCheckpoints(principal.tenantId(), batch.batchId());
        int differences = Math.abs(expected - applied);
        String checkpointDigest = checkpointDigest(
            persistence.listCheckpointDigests(principal.tenantId(), batch.batchId()));
        CanonicalJson.Result result = canonical(Map.of("batchId", batch.batchId(), "expectedRows", expected,
            "appliedRows", applied, "differenceCount", differences, "checkpointDigest", checkpointDigest));
        persistence.insertReconciliation(new ReconciliationWrite(ids.next(), principal.tenantId(), batch.batchId(),
            expected, applied, differences, result.sha256(), differences == 0 ? "MATCHED" : "MISMATCH",
            principal.userId(), command.correlationId(), now()));
        if (differences != 0) {
            transition(batch, BatchState.COMPENSATION_REQUIRED, principal, command.correlationId());
            return new ReconciliationResult(batch.batchId(), expected, applied, differences, result.sha256(), false);
        }
        transition(batch, BatchState.RECONCILED, principal, command.correlationId());
        return new ReconciliationResult(batch.batchId(), expected, applied, 0, result.sha256(), true);
    }

    /** 只有双人审批和零差异对账的批次才允许激活商品可见版本。 */
    public BatchDetail activate(BatchCommand command) {
        TrustedPrincipal principal = principalAdmin(); requireKey(command.idempotencyKey(), "idempotencyKey");
        requireCorrelation(command.correlationId());
        BatchRecord batch = requireBatch(principal.tenantId(), command.batchId());
        if (Set.of(BatchState.ACTIVATED.name(), BatchState.CLEANED.name()).contains(batch.state())) {
            return detail(batch);
        }
        ReconciliationRecord reconciliation = persistence.latestReconciliation(principal.tenantId(), batch.batchId());
        if (!Set.of(BatchState.RECONCILED.name(), BatchState.ACTIVATION_PENDING.name()).contains(batch.state())
            || reconciliation == null
            || reconciliation.differenceCount() != 0 || persistence.countApprovals(principal.tenantId(), batch.batchId()) < 2) {
            throw state("审批或对账未满足激活条件");
        }
        if (BatchState.RECONCILED.name().equals(batch.state())) {
            BatchRecord reconciled = batch;
            batch = tx(() -> transition(reconciled, BatchState.ACTIVATION_PENDING, principal, command.correlationId()));
        }
        // Catalog 激活是按 batchId 幂等的；进程在 Owner 成功后终止时，
        // 重启必须继续观察原激活命令，禁止创建新命令或回写业务事实。
        String activeBatchId = batch.batchId();
        catalog.activateBatch(activeBatchId, command.correlationId());
        BatchRecord activated = tx(() -> transition(requireBatch(principal.tenantId(), activeBatchId),
            BatchState.ACTIVATED, principal, command.correlationId()));
        return detail(activated);
    }

    @Transactional
    public BatchDetail cleanup(BatchCommand command) {
        TrustedPrincipal principal = principalAdmin(); requireKey(command.idempotencyKey(), "idempotencyKey");
        requireCorrelation(command.correlationId());
        BatchRecord batch = requireBatch(principal.tenantId(), command.batchId());
        if (BatchState.CLEANED.name().equals(batch.state())) return detail(batch);
        if (!BatchState.ACTIVATED.name().equals(batch.state())) throw state("只有 ACTIVATED 可清理 staging");
        persistence.clearStaging(principal.tenantId(), batch.batchId(), now());
        return detail(transition(batch, BatchState.CLEANED, principal, command.correlationId()));
    }

    @Transactional(readOnly = true)
    public BatchDetail detail(String batchId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorization.requireTenantAdministrator();
        return detail(requireBatch(principal.tenantId(), batchId));
    }

    /** 分页读取全部脱敏预检错误，避免 10 万行错误被截断或一次性压垮浏览器。 */
    @Transactional(readOnly = true)
    public PreflightErrorPage errors(String batchId, int page, int pageSize) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorization.requireTenantAdministrator();
        BatchRecord batch = requireBatch(principal.tenantId(), batchId);
        if (page < 1 || pageSize < 1 || pageSize > 500) {
            throw new ServiceException("DMT-PAGE-001: 页码或每页数量非法", 400);
        }
        int total = persistence.countPreflightErrors(principal.tenantId(), batch.batchId());
        int offset;
        try { offset = Math.multiplyExact(page - 1, pageSize); }
        catch (ArithmeticException exception) { throw new ServiceException("DMT-PAGE-001: 页码超限", 400); }
        List<PreflightErrorView> records = persistence.listPreflightErrorsPage(principal.tenantId(),
            batch.batchId(), offset, pageSize).stream().map(this::preflightError).toList();
        return new PreflightErrorPage(page, pageSize, total, records);
    }

    private OwnerResult applyOwner(StagingRecord row, String correlationId) {
        Map<String, Object> value = open(row);
        return switch (DataType.valueOf(row.dataType())) {
            case CATALOG -> {
                ProductMigrationResult result = catalog.importDraftProduct(new ProductMigrationCommand(row.batchId(),
                    row.rowId(), row.rowSha256(), string(value,"spuCode"), string(value,"skuCode"), string(value,"name"),
                    string(value,"categoryCode"), string(value,"categoryName"), nullable(value,"brandCode"),
                    nullable(value,"brandName"), string(value,"productType"), string(value,"unitCode"),
                    string(value,"unitName"), integer(value,"decimalScale"), number(value,"ratioNumerator"),
                    number(value,"ratioDenominator"), strings(value,"barcodes"), correlationId));
                yield owner("CATALOG", row.rowId(), Map.of("skuId", result.skuId(), "baseUnitId", result.baseUnitId(),
                    "skuCode", result.skuCode(), "state", result.state(), "rowSha256", result.rowSha256()));
            }
            case SUPPLIER -> {
                SupplierMigrationResult result = suppliers.importSupplier(new SupplierMigrationCommand(
                    string(value,"supplierId"), string(value,"supplierCode"), string(value,"supplierName"),
                    row.rowSha256(), correlationId));
                yield owner("PROCUREMENT", result.supplierId(), Map.of("supplierId", result.supplierId(),
                    "code", result.code(), "state", result.state(), "rowSha256", result.rowSha256()));
            }
            case MEMBER -> {
                MemberMigrationResult result = members.importMember(new MemberMigrationCommand(string(value,"commandId"),
                    string(value,"memberId"), string(value,"identityId"), string(value,"identityType"),
                    string(value,"identityValue"), row.rowSha256(), correlationId));
                yield owner("MEMBER", string(value,"commandId"), Map.of("memberId", result.memberId(),
                    "alias", result.alias(), "state", result.state(), "rowSha256", result.rowSha256()));
            }
            case OPENING_INVENTORY -> {
                ProductMigrationResult product = catalog.requireProduct(row.batchId(), string(value,"skuCode"));
                ApplyResult result = inventory.importOpeningInventory(new OpeningInventoryCommand(
                    string(value,"eventId"), row.batchId(), row.rowId(), string(value,"warehouseId"),
                    number(value,"storeId"), product.skuId(), product.baseUnitId(),
                    new BigDecimal(string(value,"quantity")), LocalDate.parse(string(value,"businessDate")), correlationId));
                yield owner("INVENTORY", string(value,"eventId"), Map.of("commandId", result.eventId(),
                    "ledgerCount", result.affectedLines(), "rowSha256", row.rowSha256()));
            }
        };
    }

    private OwnerResult owner(String owner, String commandId, Map<String, Object> result) {
        return new OwnerResult(owner, commandId, canonical(result).sha256());
    }

    private UploadResult replayUpload(BatchRecord batch, UploadFile command, FileRecord existing) {
        byte[] content = command.content();
        if (content.length == 0 || content.length > MigrationRules.MAX_FILE_BYTES) {
            throw new ServiceException("DMT-FILE-001: 文件为空或超过 64 MiB", 400);
        }
        String actualSha256 = MigrationRules.digest(content);
        String declaredSha256 = MigrationRules.sha256(command.declaredSha256(), "declaredSha256");
        String filename = command.originalFilename() == null ? "" : command.originalFilename().strip();
        String charset = command.charset() == null || command.charset().isBlank()
            ? "UTF-8" : command.charset().strip();
        String sourceSystem = MigrationRules.text(command.sourceSystem(), 80, "sourceSystem");
        String custody = MigrationRules.text(command.custodyReference(), 256, "custodyReference");
        if (!actualSha256.equals(declaredSha256) || !actualSha256.equals(existing.sourceSha256())
            || !command.mappingVersion().equals(existing.mappingVersion())
            || !filename.equals(existing.safeFilename()) || !charset.equals(existing.charset())
            || !sourceSystem.equals(existing.sourceSystem()) || !custody.equals(existing.custodyReference())) {
            throw new ServiceException("DMT-FILE-018: 同一批次资料类型已登记且请求内容不同", 409);
        }
        BatchRecord current = requireBatch(batch.tenantId(), batch.batchId());
        return new UploadResult(view(current), fileView(existing), existing.rowCount(), existing.errorCount());
    }

    /** 文件级安全检查失败也必须形成脱敏失败事实，不能把批次遗留在 PREFLIGHTING。 */
    private UploadResult rejectFilePreflight(BatchRecord batch, UploadFile command, TrustedPrincipal principal,
                                             DataType type, String fileId, LocalDateTime at,
                                             ServiceException exception) {
        String errorCode = code(exception.getMessage());
        String sourceSystem = MigrationRules.text(command.sourceSystem(), 80, "sourceSystem");
        String custody = MigrationRules.text(command.custodyReference(), 256, "custodyReference");
        byte[] content = command.content();
        String sourceSha256 = MigrationRules.digest(content);
        String safeFilename = "rejected-" + fileId + ".blocked";
        FileWrite write = new FileWrite(fileId, principal.tenantId(), command.batchId(), type.name(),
            command.mappingVersion(), sourceSha256, safeFilename, "REJECTED", 0, 1, "PREFLIGHT_FAILED",
            sourceSystem, custody, content.length, principal.userId(), at);
        persistence.insertFile(write);
        persistence.insertPreflightError(new PreflightErrorWrite(ids.next(), principal.tenantId(), batch.batchId(),
            fileId, type.name(), 0, null, errorCode, errorCode + ": 文件安全检查失败，原始值已隐藏", at));
        BatchRecord failed = transition(batch, BatchState.PREFLIGHT_FAILED, principal, command.correlationId());
        FileRecord file = new FileRecord(fileId, batch.batchId(), type.name(), command.mappingVersion(),
            sourceSha256, safeFilename, "REJECTED", 0, 1, "PREFLIGHT_FAILED", sourceSystem, custody);
        return new UploadResult(view(failed), fileView(file), 0, 1);
    }

    private ReconciliationResult reconciliation(ReconciliationRecord value) {
        return new ReconciliationResult(value.batchId(), value.expectedRows(), value.appliedRows(),
            value.differenceCount(), value.resultSha256(), value.differenceCount() == 0);
    }

    /** 对完整有序检查点使用长度前缀流式摘要，避免大批量摘要被数据库聚合长度截断。 */
    private String checkpointDigest(List<CheckpointDigest> checkpoints) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CheckpointDigest value : checkpoints) {
                digestPart(digest, value.rowId()); digestPart(digest, value.ownerType());
                digestPart(digest, value.dataType()); digestPart(digest, value.requestSha256());
                digestPart(digest, value.resultSha256()); digestPart(digest, value.state());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void digestPart(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private Map<String, Object> open(StagingRecord row) {
        if (!"READY".equals(row.state())) throw new ServiceException("DMT-SECURITY-007: staging 已清理", 409);
        String json = cipher.open(aad(tenantContext.requireTenantId(), row.batchId(), row.rowId(), row.dataType()),
            new SealedValue(row.cipherText(), row.keyVersion(), row.contentHmac()));
        if (!MigrationRules.digest(json).equals(row.rowSha256())) throw new ServiceException("DMT-SECURITY-005: staging 摘要漂移", 409);
        try { return objectMapper.readValue(json, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new ServiceException("DMT-SECURITY-006: staging JSON 损坏", 409); }
    }

    private BatchRecord transition(BatchRecord batch, BatchState target, TrustedPrincipal principal, String correlationId) {
        BatchState source = BatchState.valueOf(batch.state());
        if (source == target) return batch;
        if (!MigrationStates.canTransition(source, target)) throw state(source + " 不可迁移到 " + target);
        LocalDateTime at = now();
        if (persistence.changeBatchState(new StateChange(principal.tenantId(), batch.batchId(), source.name(),
            target.name(), batch.version(), at)) != 1) throw new ServiceException("DMT-STATE-002: 批次并发冲突", 409);
        int nextVersion = batch.version() + 1;
        persistence.appendStateEvent(new StateEventWrite(ids.next(), principal.tenantId(), batch.batchId(),
            source.name(), target.name(), nextVersion, principal.userId(), correlationId, at));
        String payload = json(Map.of("batchId", batch.batchId(), "from", source.name(), "to", target.name(),
            "schemaVersion", 1));
        persistence.appendOutbox(new OutboxWrite(ids.next(), principal.tenantId(), batch.batchId(),
            "business-migration.state-changed.v1", nextVersion, payload, MigrationRules.digest(payload), correlationId, at));
        persistence.appendAudit(new AuditWrite(ids.next(), principal.tenantId(), batch.batchId(),
            "MIGRATION_STATE_CHANGED", principal.userId(), MigrationRules.digest(source + ":" + target), correlationId, at));
        return requireBatch(principal.tenantId(), batch.batchId());
    }

    private void appendCreatedFacts(BatchRecord batch, TrustedPrincipal principal, LocalDateTime at) {
        persistence.appendStateEvent(new StateEventWrite(ids.next(), principal.tenantId(), batch.batchId(),
            "NONE", BatchState.UPLOADED.name(), 0, principal.userId(), batch.correlationId(), at));
        persistence.appendAudit(new AuditWrite(ids.next(), principal.tenantId(), batch.batchId(), "MIGRATION_CREATED",
            principal.userId(), batch.requestSha256(), batch.correlationId(), at));
    }

    private BatchDetail detail(BatchRecord batch) {
        String tenant = tenantContext.requireTenantId();
        List<FileRecord> files = persistence.listFiles(tenant, batch.batchId());
        List<PreflightErrorView> errors = persistence.listPreflightErrors(tenant, batch.batchId()).stream()
            .map(this::preflightError).toList();
        List<CheckpointView> checkpoints = persistence.listCheckpointSummaries(tenant, batch.batchId()).stream()
            .map(value -> new CheckpointView(value.ownerType(), value.dataType(), value.appliedCount(),
                value.failedCount(), value.resultSha256(), value.state())).toList();
        return new BatchDetail(view(batch), files.stream().map(this::fileView).toList(), errors, checkpoints);
    }

    private PreflightErrorView preflightError(PreflightErrorRecord value) {
        return new PreflightErrorView(value.errorId(), value.dataType(), value.rowNumber(), value.fieldName(),
            value.errorCode(), value.maskedMessage());
    }

    private BatchView view(BatchRecord batch) {
        String tenant = tenantContext.requireTenantId();
        List<FileRecord> files = persistence.listFiles(tenant, batch.batchId());
        int rows = files.stream().mapToInt(FileRecord::rowCount).sum();
        int errors = files.stream().mapToInt(FileRecord::errorCount).sum();
        return new BatchView(batch.batchId(), batch.state(), requested(batch), files.size(), rows, errors,
            persistence.countApprovals(tenant, batch.batchId()), persistence.countAppliedCheckpoints(tenant,
            batch.batchId()), batch.version(), batch.requestSha256(), batch.correlationId(), batch.createdAt());
    }

    private FileWrite fileWrite(UploadFile command, TrustedPrincipal principal, InspectedTable table,
                                String fileId, int rows, int errors, String state, LocalDateTime at) {
        return new FileWrite(fileId, principal.tenantId(), command.batchId(), dataType(command.dataType()).name(),
            command.mappingVersion(), table.sha256(), table.safeFilename(),
            command.charset() == null || command.charset().isBlank() ? "UTF-8" : command.charset().strip(),
            rows, errors, state, MigrationRules.text(command.sourceSystem(),80,"sourceSystem"),
            MigrationRules.text(command.custodyReference(),256,"custodyReference"), command.content().length,
            principal.userId(), at);
    }
    private FileView fileView(FileRecord value) { return new FileView(value.fileId(), value.batchId(), value.dataType(), value.mappingVersion(), value.sourceSha256(), value.safeFilename(), value.charset(), value.rowCount(), value.errorCount(), value.state(), value.sourceSystem(), value.custodyReference()); }
    private BatchRecord requireBatch(String tenant, String id) { MigrationRules.ulid(id,"batchId"); BatchRecord value=persistence.findBatch(tenant,id); if(value==null)throw new ServiceException("DMT-BATCH-001: 批次不存在或不可见",404); return value; }
    private TrustedPrincipal principalAdmin() { TrustedPrincipal value=tenantContext.requirePrincipal(); authorization.requireTenantAdministrator(); return value; }
    private Set<String> normalizedTypes(Set<String> raw) { if(raw==null||raw.isEmpty())throw new ServiceException("DMT-BATCH-002: 至少选择一种资料",400); Set<String> result=new TreeSet<>(); for(String value:raw)result.add(dataType(value).name()); return Set.copyOf(result); }
    private Set<String> requested(BatchRecord batch) { try { return Set.copyOf(objectMapper.readValue(batch.requestedTypes(), new TypeReference<Set<String>>(){})); } catch(JsonProcessingException e){throw new ServiceException("DMT-BATCH-003: 批次资料范围损坏",409);} }
    private Set<String> uploadedTypes(String tenant,String batch){Set<String> result=new LinkedHashSet<>();persistence.listFiles(tenant,batch).stream().filter(v->"PREFLIGHT_PASSED".equals(v.state())).forEach(v->result.add(v.dataType()));return result;}
    private DataType dataType(String raw){try{return DataType.valueOf(raw==null?"":raw.strip().toUpperCase());}catch(IllegalArgumentException e){throw new ServiceException("DMT-MAPPING-006: 资料类型无效",400);}}
    private CanonicalJson.Result canonical(Map<String,Object> value){return CanonicalJson.from(value,1024*1024);}
    private String json(Object value){try{return objectMapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new ServiceException("DMT-JSON-001: 规范对象无法序列化",409);}}
    private String string(Map<String,Object> value,String key){Object raw=value.get(key);if(raw==null)throw new ServiceException("DMT-STAGE-001: staging缺少字段"+key,409);return String.valueOf(raw);}
    private String nullable(Map<String,Object> value,String key){Object raw=value.get(key);return raw==null?null:String.valueOf(raw);}
    private long number(Map<String,Object> value,String key){try{return Long.parseLong(string(value,key));}catch(NumberFormatException e){throw new ServiceException("DMT-STAGE-002: staging数字字段损坏",409);}}
    private int integer(Map<String,Object> value,String key){return Math.toIntExact(number(value,key));}
    private List<String> strings(Map<String,Object> value,String key){Object raw=value.get(key);if(!(raw instanceof List<?> list))return List.of();return list.stream().map(String::valueOf).toList();}
    private String aad(String tenant,String batch,String row,String type){return tenant+":"+batch+":"+row+":"+type;}
    private void requireKey(String value,String field){MigrationRules.text(value,128,field);}
    private void requireCorrelation(String value){MigrationRules.text(value,64,"correlationId");}
    private String code(String message){if(message==null)return "DMT-PREFLIGHT-999";int i=message.indexOf(':');return i>0?message.substring(0,i):"DMT-PREFLIGHT-999";}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);}
    private ServiceException state(String reason){return new ServiceException("DMT-STATE-001: "+reason,409);}
    private ServiceException idem(){return new ServiceException("DMT-IDEM-001: 同一幂等键对应不同内容",409);}
    private <T> T tx(java.util.function.Supplier<T> work){return new TransactionTemplate(transactionManager).execute(status->work.get());}
    /**
     * 单行 Owner 应用结果，用于保存稳定检查点。
     * @param owner 写入事实的数据 Owner
     * @param commandId Owner 接收的稳定命令标识
     * @param resultSha256 Owner 结果内容摘要
     */
    private record OwnerResult(String owner,String commandId,String resultSha256){}
}
