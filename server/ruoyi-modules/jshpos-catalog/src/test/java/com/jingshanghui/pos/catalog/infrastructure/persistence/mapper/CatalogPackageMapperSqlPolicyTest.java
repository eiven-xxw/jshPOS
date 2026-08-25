package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** 商品数据包必须沿用公开 SKU 生命周期，不能依赖不存在的 SPU 激活入口。 */
class CatalogPackageMapperSqlPolicyTest {

    @Test
    void packageProductsUseSkuAndUnitLifecycleWithoutUnreachableSpuState() throws Exception {
        var method = CatalogMapper.class.getMethod("listProductPackageRows", String.class);
        var select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        String sql = String.join(" ", Arrays.asList(select.value())).replaceAll("\\s+", " ").toLowerCase();

        assertThat(sql)
            .contains("s.tenant_id=#{tenantid}")
            .contains("s.status='active'")
            .contains("u.status='active'")
            .doesNotContain("p.status='active'");
    }
}
