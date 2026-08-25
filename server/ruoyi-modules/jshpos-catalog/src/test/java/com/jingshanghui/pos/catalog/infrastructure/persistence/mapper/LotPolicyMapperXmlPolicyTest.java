package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 批次策略 Mapper 的构造器映射回归，防止 primitive record 参数被解析为包装类型。 */
class LotPolicyMapperXmlPolicyTest {

    @Test
    void shouldUsePrimitiveAliasesForPrimitiveRecordComponents() throws IOException {
        try (var input = getClass().getResourceAsStream("/mapper/catalog/LotPolicyMapper.xml")) {
            assertThat(input).as("LotPolicyMapper.xml 应进入测试类路径").isNotNull();
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(xml).contains("column=\"enabled\" javaType=\"_boolean\"");
            assertThat(xml).contains("column=\"nearExpiryDays\" javaType=\"_int\"");
            assertThat(xml).doesNotContain("column=\"enabled\" javaType=\"boolean\"");
            assertThat(xml).doesNotContain("column=\"nearExpiryDays\" javaType=\"int\"");
        }
    }
}
