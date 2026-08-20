package com.jingshanghui.pos.release.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.release.infrastructure.persistence.mapper.ReleasePersistenceMapper;
import org.apache.ibatis.annotations.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Mapper可信上下文、XML_ONLY和Owner写边界静态攻击测试。 */
class ReleasePersistencePolicyTest {
    @Test void everyMapperCallRequiresTrustedTenantContext() {
        TrustedTenantContext context=mock(TrustedTenantContext.class);
        new ReleaseStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }

    @Test void keepsComplexSqlInXmlAndForbidsCrossOwnerWritesOrInterpolation() throws Exception {
        for(var method: ReleasePersistenceMapper.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Select.class)).as(method.getName()).isNull();
            assertThat(method.getAnnotation(Insert.class)).as(method.getName()).isNull();
            assertThat(method.getAnnotation(Update.class)).as(method.getName()).isNull();
            assertThat(method.getAnnotation(Delete.class)).as(method.getName()).isNull();
        }
        String xml;
        try(var stream=getClass().getResourceAsStream("/mapper/release/ReleasePersistenceMapper.xml")) {
            assertThat(stream).isNotNull(); xml=new String(stream.readAllBytes(),StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(xml).contains("tenant_id=#{tenantid}","state=#{fromstate}","version_no=#{expectedversion}",
            "insert into upg_release_event","insert into upg_audit");
        assertThat(xml).doesNotContain("${","select *","delete from","update ord_","update pay_","update inv_",
            "update prm_","update dev_","update pos_sync_");
    }
}
