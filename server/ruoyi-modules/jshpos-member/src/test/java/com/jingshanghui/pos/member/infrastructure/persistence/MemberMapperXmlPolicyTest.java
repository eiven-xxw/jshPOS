package com.jingshanghui.pos.member.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** 静态阻断缺租户 SQL、注解 SQL、PII 输出和跨 Owner 写入。 */
class MemberMapperXmlPolicyTest {
    @Test void mapperUsesExplicitTenantPredicatesAndAppendOnlyLedgers() throws Exception {
        try (var stream=getClass().getResourceAsStream("/mapper/member/MemberPersistenceMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml=new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *").contains("tenant_id=#{tenantid}")
                .contains("for update").contains("insert into mbr_consent_ledger")
                .contains("insert into mbr_privacy_history").contains("insert into mbr_member_link_ledger")
                .contains("insert into mbr_event_outbox")
                .doesNotContain("delete from mbr_").doesNotContain("update mbr_consent_ledger")
                .doesNotContain("update mbr_privacy_history").doesNotContain("update mbr_member_link_ledger")
                .doesNotContain("update ord_").doesNotContain("update pay_").doesNotContain("update inv_")
                .doesNotContain("select cipher_text");
        }
    }

    @Test void mapperInterfaceContainsNoSqlAnnotations() throws Exception {
        String source=Files.readString(Path.of(System.getProperty("user.dir"),
            "src/main/java/com/jingshanghui/pos/member/infrastructure/persistence/mapper/MemberPersistenceMapper.java"));
        assertThat(source).doesNotContain("@Select").doesNotContain("@Insert")
            .doesNotContain("@Update").doesNotContain("@Delete");
    }

    @Test void pointsMapperLocksProjectionsAndNeverUpdatesLedgerOrAllocation() throws Exception {
        try(var stream=getClass().getResourceAsStream("/mapper/member/PointsPersistenceMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml=new String(stream.readAllBytes(),StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *").contains("tenant_id=#{tenantid}")
                .contains("for update").contains("insert into mbr_points_ledger")
                .contains("insert into mbr_points_allocation").contains("insert into mbr_level_history")
                .contains("business_date").contains("actor_user_id").contains("approval_ref")
                .contains("case when expires_at is null then 1 else 0 end")
                .doesNotContain("update mbr_points_ledger").doesNotContain("delete from mbr_points")
                .doesNotContain("update mbr_points_allocation").doesNotContain("update mbr_level_history");
        }
        String source=Files.readString(Path.of(System.getProperty("user.dir"),
            "src/main/java/com/jingshanghui/pos/member/infrastructure/persistence/mapper/PointsPersistenceMapper.java"));
        assertThat(source).doesNotContain("@Select").doesNotContain("@Insert")
            .doesNotContain("@Update").doesNotContain("@Delete");
    }
}
