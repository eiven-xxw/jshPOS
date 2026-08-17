package com.jingshanghui.pos.resilience.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

/** BAK Mapper 必须显式字段、具名条件迁移且不提供删除或通用更新。 */
class BackupMapperXmlPolicyTest {
    @Test void mapperKeepsControlledAndAppendOnlyBoundaries() throws Exception {
        String xml=Files.readString(Path.of("src/main/resources/mapper/resilience/BackupPersistenceMapper.xml"));
        assertThat(xml).doesNotContain("SELECT *","<delete", "${");
        assertThat(xml).contains("AND state=#{expectedState}","INSERT INTO bak_backup_object",
            "INSERT INTO bak_restore_check","INSERT INTO bak_audit");
    }
}
