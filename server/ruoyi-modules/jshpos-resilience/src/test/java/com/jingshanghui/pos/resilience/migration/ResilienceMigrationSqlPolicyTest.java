package com.jingshanghui.pos.resilience.migration;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

/** V38/V39 表、中文注释、权限和不可变触发器静态门禁。 */
class ResilienceMigrationSqlPolicyTest {
    @Test void migrationsDeclareOwnersCommentsPermissionsAndGuards() throws Exception {
        String core=Files.readString(Path.of("src/main/resources/db/migration/V202608180038__gate6a_backup_recovery.sql"));
        String guard=Files.readString(Path.of("src/main/resources/db/migration/V202608180039__gate6a_backup_permissions_guards.sql"));
        assertThat(core).contains("CREATE TABLE bak_backup_set","CREATE TABLE bak_backup_object",
            "CREATE TABLE bak_restore_drill","CREATE TABLE bak_restore_check","CREATE TABLE bak_audit",
            "COMMENT '服务端备份ULID", "AES-GCM", "CONTROLLED_WRITE/XML", "APPEND_ONLY/XML");
        assertThat(guard).contains("backup:create","backup:restore:execute","backup:evidence:read",
            "trg_bak_object_no_update","trg_bak_check_no_delete","trg_bak_set_guard","trg_bak_drill_guard");
        assertThat(core+guard).doesNotContain("DROP TABLE","DROP COLUMN","FOREIGN_KEY_CHECKS=0");
    }
}
