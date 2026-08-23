package com.jingshanghui.pos.catalog.infrastructure.persistence;

import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.MemberPricePersistenceMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 约束会员价复杂 SQL 必须位于 XML，且每条路径显式携带租户条件。 */
class MemberPriceXmlPolicyTest {

    @Test
    void mapperKeepsSqlInXmlAndScopesEveryStatementByTenant() throws IOException {
        assertThat(MemberPricePersistenceMapper.class.getDeclaredMethods()).allSatisfy(method ->
            assertThat(method.getAnnotations()).noneMatch(annotation ->
                annotation.annotationType().getPackageName().equals("org.apache.ibatis.annotations")
                    && !annotation.annotationType().getSimpleName().equals("Param")));

        String xml = resource("/mapper/catalog/MemberPricePersistenceMapper.xml").toLowerCase();
        assertThat(xml).contains("tenant_id", "where tenant_id=#{tenantid}",
            "v.tenant_id=#{tenantid}", "incoming.tenant_id=#{tenantid}");
        assertThat(xml).doesNotContain("delete from", "update mem_", "update ord_", "update prm_",
            "update inv_", "update pay_");
    }

    private String resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream(name)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
