package com.jingshanghui.pos.integration.infrastructure.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证跨 Owner 菜单主键冲突只通过组合根前向修复处理，不回写已发布迁移。 */
class MenuIdCollisionForwardRepairPolicyTest {
    @Test
    void repairIsIdempotentFailsClosedAndPreservesRoleBindings() throws IOException {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/beforeEachMigrate__repair_gate4c_gate7c_menu_ids.sql")) {
            assertThat(input).as("forward repair callback").isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql)
                .contains("insert ignore into sys_role_menu")
                .contains("delete role_menu")
                .contains("9200540")
                .contains("9201540")
                .contains("inventory:cost-balance:read")
                .contains("signal sqlstate '45000'")
                .doesNotContain("migration:read' then");
        }
    }
}
