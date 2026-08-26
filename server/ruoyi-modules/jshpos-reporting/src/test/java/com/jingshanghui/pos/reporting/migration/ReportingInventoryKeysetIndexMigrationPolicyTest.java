package com.jingshanghui.pos.reporting.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-T2G10A-024 批准的 RPT-INVENTORY keyset 索引前向迁移静态门禁。
 *
 * <p>本测试先于 V89 迁移加入，用于证明缺少唯一获批索引时会稳定失败；迁移加入后继续
 * 固化索引名、列顺序、在线 DDL 约束以及“不得删除既有索引”的范围边界。</p>
 */
class ReportingInventoryKeysetIndexMigrationPolicyTest {
    private static final String MIGRATION =
        "/db/migration/V202608260089__reporting_inventory_keyset_index.sql";

    @Test
    void requiresOnlyApprovedV89KeysetIndex() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("approved V89 RPT-INVENTORY keyset index migration").isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        }

        assertThat(sql)
            .contains("alter table rpt_inventory_cost_daily")
            .contains("add index idx_rpt_inventory_keyset ( tenant_id, projection_version, business_date, "
                + "store_id, warehouse_id, sku_id, currency )")
            .contains("algorithm=inplace")
            .contains("lock=none")
            .doesNotContain("drop index")
            .doesNotContain("drop table")
            .doesNotContain("modify column")
            .doesNotContain("change column");
    }
}
