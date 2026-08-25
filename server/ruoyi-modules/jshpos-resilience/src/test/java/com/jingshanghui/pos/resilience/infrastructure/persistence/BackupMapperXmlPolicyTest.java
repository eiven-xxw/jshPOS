package com.jingshanghui.pos.resilience.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.jingshanghui.pos.resilience.infrastructure.persistence.mapper.BackupPersistenceMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

/** BAK Mapper 必须显式字段、具名条件迁移且不提供删除或通用更新。 */
class BackupMapperXmlPolicyTest {
    @Test void mapperKeepsControlledAndAppendOnlyBoundaries() throws Exception {
        String xml=Files.readString(Path.of("src/main/resources/mapper/resilience/BackupPersistenceMapper.xml"));
        assertThat(xml).doesNotContain("SELECT *","<delete", "${");
        assertThat(xml).contains("AND state=#{expectedState}","INSERT INTO bak_backup_object",
            "INSERT INTO bak_restore_check","INSERT INTO bak_audit", "tenant_ids_csv", "tenant_scope_sha256");
        InterceptorIgnore boundary = BackupPersistenceMapper.class.getAnnotation(InterceptorIgnore.class);
        assertThat(boundary).as("无tenant_id的多租户备份集合必须显式声明拦截边界").isNotNull();
        assertThat(boundary.tenantLine()).isEqualTo("true");
        assertThat(boundary.dataPermission()).isEqualTo("true");
    }
}
