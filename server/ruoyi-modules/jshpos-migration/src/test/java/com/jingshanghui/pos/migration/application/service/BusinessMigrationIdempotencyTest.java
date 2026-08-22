package com.jingshanghui.pos.migration.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.BusinessMigrationCatalogPort;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.port.BusinessMigrationInventoryPort;
import com.jingshanghui.pos.member.application.port.BusinessMigrationMemberPort;
import com.jingshanghui.pos.migration.application.model.MigrationModels.BatchCommand;
import com.jingshanghui.pos.migration.application.model.MigrationModels.UploadFile;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.ApprovalRecord;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.BatchRecord;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.FileRecord;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.CheckpointRecord;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.StagingRecord;
import com.jingshanghui.pos.migration.application.port.MigrationStagingCipher;
import com.jingshanghui.pos.migration.domain.MigrationRowNormalizer;
import com.jingshanghui.pos.migration.domain.MigrationRules;
import com.jingshanghui.pos.migration.infrastructure.file.MigrationFileInspector;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.procurement.application.port.BusinessMigrationSupplierPort;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 文件与审批重复提交必须返回原事实，同键异内容必须失败关闭。 */
class BusinessMigrationIdempotencyTest {
    private static final String BATCH = "01K2A000000000000000000001";
    private static final String FILE = "01K2A000000000000000000002";
    private static final String TENANT = "TENANT_A";
    private BusinessMigrationPersistencePort persistence;
    private MigrationFileInspector inspector;
    private MigrationStagingCipher cipher;
    private BusinessMigrationService service;

    @BeforeEach
    void setUp() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        when(context.requireTenantId()).thenReturn(TENANT);
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT, 101L, 10L, "admin"));
        persistence = mock(BusinessMigrationPersistencePort.class);
        inspector = mock(MigrationFileInspector.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T02:00:00Z"), ZoneOffset.UTC);
        cipher = mock(MigrationStagingCipher.class);
        service = new BusinessMigrationService(context, mock(ScopeAuthorizationService.class), persistence,
            inspector, mock(MigrationRowNormalizer.class), cipher,
            mock(BusinessMigrationCatalogPort.class), mock(BusinessMigrationSupplierPort.class),
            mock(BusinessMigrationMemberPort.class), mock(BusinessMigrationInventoryPort.class),
            new UlidGenerator(clock), new ObjectMapper(), clock, new NoOpTransactionManager());
        when(persistence.listPreflightErrors(TENANT, BATCH)).thenReturn(List.of());
        when(persistence.listCheckpointSummaries(TENANT, BATCH)).thenReturn(List.of());
    }

    @Test
    void replaysSameRegisteredFileWithoutParsingOrOwnerWritesAndRejectsReplacement() {
        byte[] content = "supplierCode,supplierName\nSUP-1,测试供应商\n".getBytes(StandardCharsets.UTF_8);
        String sha = MigrationRules.digest(content);
        BatchRecord batch = batch("READY");
        FileRecord file = new FileRecord(FILE, BATCH, "SUPPLIER", "1.0", sha, "supplier.csv",
            "UTF-8", 1, 0, "PREFLIGHT_PASSED", "虚构旧系统", "CUSTODY:SYN-1");
        when(persistence.findBatch(TENANT, BATCH)).thenReturn(batch);
        when(persistence.listFiles(TENANT, BATCH)).thenReturn(List.of(file));

        var replay = service.upload(upload(content, sha));

        assertThat(replay.acceptedRows()).isEqualTo(1);
        assertThat(replay.file().fileId()).isEqualTo(FILE);
        verifyNoInteractions(inspector);
        verify(persistence, never()).insertStagingRow(any());

        byte[] changed = "supplierCode,supplierName\nSUP-2,另一个供应商\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.upload(upload(changed, MigrationRules.digest(changed))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-018");
    }

    @Test
    void replaysSameApprovalAfterBatchAdvancedAndRejectsDifferentReason() {
        BatchRecord batch = batch("APPROVED");
        when(persistence.findBatch(TENANT, BATCH)).thenReturn(batch);
        when(persistence.listFiles(TENANT, BATCH)).thenReturn(List.of());
        String reasonHash = MigrationRules.digest("已复核");
        when(persistence.findApprovalByIdempotency(TENANT, BATCH, "idem-approval"))
            .thenReturn(new ApprovalRecord("01K2A000000000000000000009", BATCH, 101L, reasonHash,
                "idem-approval"));

        var replay = service.approve(new BatchCommand(BATCH, "idem-approval", "已复核", "trace-approval"));
        assertThat(replay.batch().state()).isEqualTo("APPROVED");
        verify(persistence, never()).insertApproval(any());

        assertThatThrownBy(() -> service.approve(new BatchCommand(BATCH, "idem-approval", "篡改理由",
            "trace-approval"))).isInstanceOf(ServiceException.class).hasMessageContaining("DMT-IDEM-001");
    }

    @Test
    void recordsFileSecurityFailureAndDoesNotLeaveBatchPreflighting() {
        BatchRecord uploaded = batch("UPLOADED");
        BatchRecord preflighting = new BatchRecord(BATCH, TENANT, "[\"SUPPLIER\"]", "PREFLIGHTING",
            "idem-create", "a".repeat(64), "trace-create", 5,
            LocalDateTime.of(2026, 8, 22, 0, 0));
        BatchRecord failed = new BatchRecord(BATCH, TENANT, "[\"SUPPLIER\"]", "PREFLIGHT_FAILED",
            "idem-create", "a".repeat(64), "trace-create", 6,
            LocalDateTime.of(2026, 8, 22, 0, 0));
        when(persistence.findBatch(TENANT, BATCH)).thenReturn(uploaded, preflighting, failed);
        when(persistence.listFiles(TENANT, BATCH)).thenReturn(List.of());
        when(persistence.changeBatchState(any())).thenReturn(1);
        when(inspector.inspect(any(), any(), any(), any()))
            .thenThrow(new ServiceException("DMT-FILE-001: 文件为空或超过 64 MiB", 400));
        byte[] empty = new byte[0];

        var result = service.upload(upload(empty, MigrationRules.digest(empty)));

        assertThat(result.batch().state()).isEqualTo("PREFLIGHT_FAILED");
        assertThat(result.errorCount()).isEqualTo(1);
        verify(persistence).insertFile(argThat(value -> "PREFLIGHT_FAILED".equals(value.state())
            && value.fileBytes() == 0 && "REJECTED".equals(value.charset())));
        verify(persistence).insertPreflightError(argThat(value -> "DMT-FILE-001".equals(value.errorCode())
            && !value.maskedMessage().contains("supplier.csv")));
    }

    @Test
    void resumesFromAppliedCheckpointWithoutReopeningStagingOrRegeneratingOwnerCommand() {
        BatchRecord importing = batch("IMPORTING");
        BatchRecord imported = new BatchRecord(BATCH, TENANT, "[\"SUPPLIER\"]", "IMPORTED",
            "idem-create", "a".repeat(64), "trace-create", 5,
            LocalDateTime.of(2026, 8, 22, 0, 0));
        StagingRecord row = new StagingRecord("01K2A000000000000000000010", BATCH, FILE, "SUPPLIER", 2,
            "b".repeat(64), "cipher", "v1", "c".repeat(64), "READY");
        CheckpointRecord checkpoint = new CheckpointRecord("01K2A000000000000000000011", BATCH, row.rowId(),
            "PROCUREMENT", "SUPPLIER", "SUP-1", row.rowSha256(), "d".repeat(64), "APPLIED");
        when(persistence.findBatch(TENANT, BATCH)).thenReturn(importing, importing, imported);
        when(persistence.listStagingRows(TENANT, BATCH)).thenReturn(List.of(row));
        when(persistence.findCheckpoint(TENANT, BATCH, row.rowId())).thenReturn(checkpoint);
        when(persistence.countAppliedCheckpoints(TENANT, BATCH)).thenReturn(1);
        when(persistence.changeBatchState(any())).thenReturn(1);

        var result = service.resume(new BatchCommand(BATCH, "idem-resume", "沿原检查点恢复", "trace-resume"));

        assertThat(result.batch().state()).isEqualTo("IMPORTED");
        verify(cipher, never()).open(any(), any());
        verify(persistence, never()).insertCheckpoint(any());
    }

    private UploadFile upload(byte[] content, String sha) {
        return new UploadFile(BATCH, "SUPPLIER", "1.0", "supplier.csv", "UTF-8", "虚构旧系统",
            "CUSTODY:SYN-1", sha, content, "trace-upload");
    }

    private BatchRecord batch(String state) {
        return new BatchRecord(BATCH, TENANT, "[\"SUPPLIER\"]", state, "idem-create", "a".repeat(64),
            "trace-create", 4, LocalDateTime.of(2026, 8, 22, 0, 0));
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }
}
