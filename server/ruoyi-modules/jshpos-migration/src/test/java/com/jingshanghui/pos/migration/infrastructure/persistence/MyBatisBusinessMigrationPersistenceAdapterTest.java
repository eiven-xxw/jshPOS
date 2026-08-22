package com.jingshanghui.pos.migration.infrastructure.persistence;

import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.CheckpointDigest;
import com.jingshanghui.pos.migration.infrastructure.persistence.mapper.BusinessMigrationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 大批量检查点摘要必须由完整有序行计算，不依赖会截断的 GROUP_CONCAT。 */
class MyBatisBusinessMigrationPersistenceAdapterTest {
    @Test
    void createsDeterministicOwnerSummariesFromEveryCheckpointDigest() {
        BusinessMigrationMapper mapper = mock(BusinessMigrationMapper.class);
        var rows = List.of(
            row("01K2A000000000000000000001", "CATALOG", "CATALOG", "a", "b", "APPLIED"),
            row("01K2A000000000000000000002", "CATALOG", "CATALOG", "c", "d", "APPLIED"),
            row("01K2A000000000000000000003", "MEMBER", "MEMBER", "e", "f", "FAILED"));
        when(mapper.listCheckpointDigests("TENANT_A", "BATCH_A")).thenReturn(rows);
        MyBatisBusinessMigrationPersistenceAdapter adapter =
            new MyBatisBusinessMigrationPersistenceAdapter(mapper);

        var summaries = adapter.listCheckpointSummaries("TENANT_A", "BATCH_A");

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).appliedCount()).isEqualTo(2);
        assertThat(summaries.get(0).failedCount()).isZero();
        assertThat(summaries.get(0).resultSha256()).matches("^[a-f0-9]{64}$");
        assertThat(summaries.get(1).state()).isEqualTo("FAILED");

        when(mapper.listCheckpointDigests("TENANT_A", "BATCH_A")).thenReturn(List.of(
            row("01K2A000000000000000000001", "CATALOG", "CATALOG", "a", "changed", "APPLIED")));
        assertThat(adapter.listCheckpointSummaries("TENANT_A", "BATCH_A").get(0).resultSha256())
            .isNotEqualTo(summaries.get(0).resultSha256());
    }

    private CheckpointDigest row(String rowId, String owner, String type, String request,
                                 String result, String state) {
        return new CheckpointDigest(rowId, owner, type, request.repeat(64), result.repeat(64), state);
    }
}
