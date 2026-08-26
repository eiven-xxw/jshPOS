package com.jingshanghui.pos.reporting.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-T2G10A-018 批准的 RPT-SALES keyset 索引前向迁移静态门禁。
 *
 * <p>本测试先于 V88 迁移加入，用于证明缺少唯一获批索引时会稳定失败；迁移加入后继续
 * 固化索引名、列顺序、在线 DDL 约束以及“不得删除既有索引”的范围边界。</p>
 */
class ReportingSalesKeysetIndexMigrationPolicyTest {
    private static final String MIGRATION =
        "/db/migration/V202608260088__reporting_sales_keyset_index.sql";

    @Test
    void requiresOnlyApprovedV88KeysetIndex() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("approved V88 RPT-SALES keyset index migration").isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        }

        assertThat(sql)
            .contains("alter table rpt_sales_daily")
            .contains("add index idx_rpt_sales_keyset ( tenant_id, projection_version, business_date, "
                + "store_id, terminal_id, cashier_id, currency )")
            .contains("algorithm=inplace")
            .contains("lock=none")
            .doesNotContain("drop index")
            .doesNotContain("drop table")
            .doesNotContain("modify column")
            .doesNotContain("change column");
    }
}
