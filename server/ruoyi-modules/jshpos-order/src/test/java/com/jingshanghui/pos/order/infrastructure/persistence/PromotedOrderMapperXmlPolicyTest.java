package com.jingshanghui.pos.order.infrastructure.persistence;

import com.jingshanghui.pos.order.infrastructure.persistence.mapper.PromotedOrderMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证促销订单不可变事实只能经显式 XML SQL 写入，且不能越权修改 Promotion Owner。 */
class PromotedOrderMapperXmlPolicyTest {

    @Test
    void mapperUsesExplicitXmlOnlyInsertsWithTrustedTenantColumns() throws Exception {
        for (Method method : PromotedOrderMapper.class.getDeclaredMethods()) {
            assertThat(method.getAnnotations()).as(method.getName()).isEmpty();
            assertThat(method.getName()).startsWith("insert");
        }
        String xml;
        try (var stream = getClass().getResourceAsStream("/mapper/order/PromotedOrderMapper.xml")) {
            assertThat(stream).isNotNull();
            xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(xml).contains("tenant_id", "#{tenantid}", "insert into ord_sales_order",
            "insert into ord_order_line", "insert into ord_promotion_binding");
        assertThat(xml).doesNotContain("select *", "update ", "delete ", "insert into prm_", "update prm_");
    }
}
