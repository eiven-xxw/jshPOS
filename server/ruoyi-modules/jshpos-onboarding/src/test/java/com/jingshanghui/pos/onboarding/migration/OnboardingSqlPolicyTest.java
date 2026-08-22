package com.jingshanghui.pos.onboarding.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态证明 Onboarding Owner 具备租户复合约束、只追加历史和受控权限。 */
class OnboardingSqlPolicyTest {
    @Test
    void freezesOwnerTablesCompositeKeysAndAppendOnlyGuards() throws IOException {
        String sql = resource("/db/migration/V202608230066__gate7c_store_onboarding.sql").toLowerCase();
        for (String table : new String[]{"onb_plan", "onb_config_snapshot", "onb_approval",
            "onb_step_checkpoint", "onb_check_result", "onb_command_result", "onb_state_event",
            "onb_audit_event", "onb_outbox"}) {
            assertThat(sql).contains("create table " + table);
        }
        assertThat(sql).contains("foreign key (tenant_id,source_store_id)")
            .contains("foreign key (tenant_id,target_store_id)")
            .contains("trg_onb_plan_guard")
            .contains("trg_onb_approval_no_delete")
            .contains("trg_onb_checkpoint_no_delete")
            .contains("trg_onb_check_no_delete")
            .contains("trg_onb_command_no_delete")
            .contains("trg_onb_state_no_delete")
            .contains("trg_onb_audit_no_delete")
            .doesNotContain("insert into ord_")
            .doesNotContain("insert into inv_")
            .doesNotContain("insert into pay_")
            .doesNotContain("insert into cat_");
    }

    @Test
    void usesControlledXmlAndDoesNotExposeCrossOwnerSql() throws IOException {
        String xml = resource("/mapper/onboarding/OnboardingMapper.xml").toLowerCase();
        assertThat(xml).contains("tenant_id=#{tenantid}")
            .contains("id=\"changestate\"")
            .contains("id=\"appendoutbox\"")
            .doesNotContain("select *")
            .doesNotContain("update jsh_")
            .doesNotContain("update cat_")
            .doesNotContain("update inv_")
            .doesNotContain("update shf_");
    }

    @Test
    void registersOnlyOnboardingPermissionsAndVueRoute() throws IOException {
        String sql = resource("/db/migration/V202608230067__gate7c_store_onboarding_permissions.sql").toLowerCase();
        assertThat(sql).contains("operations/store-onboarding/index")
            .contains("onboarding:plan:create").contains("onboarding:plan:read")
            .contains("onboarding:plan:approve").contains("onboarding:plan:preflight")
            .contains("onboarding:plan:apply").contains("onboarding:plan:check")
            .contains("onboarding:plan:open").contains("onboarding:plan:cancel")
            .doesNotContain("payment sdk").doesNotContain("hardware sdk");
    }

    private String resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
