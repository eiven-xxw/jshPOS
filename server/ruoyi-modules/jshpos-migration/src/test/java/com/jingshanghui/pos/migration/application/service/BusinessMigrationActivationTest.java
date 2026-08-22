package com.jingshanghui.pos.migration.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.BusinessMigrationCatalogPort;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.port.BusinessMigrationInventoryPort;
import com.jingshanghui.pos.member.application.port.BusinessMigrationMemberPort;
import com.jingshanghui.pos.migration.application.model.MigrationModels.BatchCommand;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.BatchRecord;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.ReconciliationRecord;
import com.jingshanghui.pos.migration.application.port.MigrationStagingCipher;
import com.jingshanghui.pos.migration.domain.MigrationRowNormalizer;
import com.jingshanghui.pos.migration.infrastructure.file.MigrationFileInspector;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.procurement.application.port.BusinessMigrationSupplierPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 激活中断后必须继续原 batchId，禁止生成替代命令或错误回滚已成功 Owner 事实。 */
class BusinessMigrationActivationTest {
    private static final String BATCH = "01K2A000000000000000000001";
    private BusinessMigrationPersistencePort persistence;
    private BusinessMigrationCatalogPort catalog;
    private AtomicReference<BatchRecord> current;
    private BusinessMigrationService service;

    @BeforeEach
    void setUp() {
        TrustedTenantContext tenant = mock(TrustedTenantContext.class);
        when(tenant.requireTenantId()).thenReturn("TENANT_A");
        when(tenant.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 10L, "approver-a"));
        persistence = mock(BusinessMigrationPersistencePort.class);
        catalog = mock(BusinessMigrationCatalogPort.class);
        current = new AtomicReference<>(batch("ACTIVATION_PENDING", 8));
        when(persistence.findBatch("TENANT_A", BATCH)).thenAnswer(invocation -> current.get());
        when(persistence.latestReconciliation("TENANT_A", BATCH))
            .thenReturn(new ReconciliationRecord("01K2A000000000000000000099", BATCH, 4, 4, 0,
                "a".repeat(64), "MATCHED"));
        when(persistence.countApprovals("TENANT_A", BATCH)).thenReturn(2);
        when(persistence.listFiles("TENANT_A", BATCH)).thenReturn(List.of());
        when(persistence.listPreflightErrors("TENANT_A", BATCH)).thenReturn(List.of());
        when(persistence.listCheckpointSummaries("TENANT_A", BATCH)).thenReturn(List.of());
        when(persistence.changeBatchState(any())).thenAnswer(invocation -> {
            var change = invocation.getArgument(0, BusinessMigrationPersistencePort.StateChange.class);
            BatchRecord old = current.get();
            current.set(new BatchRecord(old.batchId(), old.tenantId(), old.requestedTypes(), change.toState(),
                old.idempotencyKey(), old.requestSha256(), old.correlationId(), old.version() + 1, old.createdAt()));
            return 1;
        });
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T01:00:00Z"), ZoneOffset.UTC);
        service = new BusinessMigrationService(tenant, mock(ScopeAuthorizationService.class), persistence,
            mock(MigrationFileInspector.class), mock(MigrationRowNormalizer.class), mock(MigrationStagingCipher.class),
            catalog, mock(BusinessMigrationSupplierPort.class), mock(BusinessMigrationMemberPort.class),
            mock(BusinessMigrationInventoryPort.class), new UlidGenerator(clock), new ObjectMapper(), clock,
            new NoOpTransactionManager());
    }

    @Test
    void resumesOriginalActivationPendingBatchAndCommitsNamedState() {
        when(catalog.activateBatch(BATCH, "trace-activate")).thenReturn(4);

        var detail = service.activate(new BatchCommand(BATCH, "idem-activate", "零差异激活", "trace-activate"));

        assertThat(detail.batch().state()).isEqualTo("ACTIVATED");
        verify(catalog).activateBatch(BATCH, "trace-activate");
        verify(persistence).changeBatchState(argThat(change -> change.fromState().equals("ACTIVATION_PENDING")
            && change.toState().equals("ACTIVATED")));
    }

    @Test
    void leavesPendingForSafeRetryWhenOwnerResultIsUnknown() {
        doThrow(new IllegalStateException("synthetic interruption")).when(catalog)
            .activateBatch(BATCH, "trace-activate");

        assertThatThrownBy(() -> service.activate(new BatchCommand(BATCH, "idem-activate", "零差异激活",
            "trace-activate"))).isInstanceOf(IllegalStateException.class);

        assertThat(current.get().state()).isEqualTo("ACTIVATION_PENDING");
        verify(persistence, never()).changeBatchState(any());
    }

    private BatchRecord batch(String state, int version) {
        return new BatchRecord(BATCH, "TENANT_A", "[\"CATALOG\"]", state, "idem-create",
            "b".repeat(64), "trace-create", version, LocalDateTime.of(2026, 8, 22, 0, 0));
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }
}
