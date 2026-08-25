package com.jingshanghui.pos.resilience.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.jingshanghui.pos.resilience.infrastructure.persistence.mapper.StoreOnboardingBackupMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 开店检查恢复证据查询的可信租户范围与拦截器边界门禁。 */
class StoreOnboardingBackupMapperPolicyTest {

    @Test
    void globalRestoreCatalogQueryMustOwnTenantScopeExplicitly() throws Exception {
        InterceptorIgnore ignore = StoreOnboardingBackupMapper.class.getAnnotation(InterceptorIgnore.class);
        assertThat(ignore).as("全局恢复目录无 tenant_id，必须显式关闭自动租户/数据权限列注入").isNotNull();
        assertThat(ignore.tenantLine()).isEqualTo("true");
        assertThat(ignore.dataPermission()).isEqualTo("true");

        String xml = Files.readString(Path.of(
            "src/main/resources/mapper/resilience/StoreOnboardingBackupMapper.xml"));
        assertThat(xml).contains("FIND_IN_SET(#{tenantId},b.tenant_ids_csv)&gt;0");
        assertThat(xml).doesNotContain("${tenantId}");
    }
}
