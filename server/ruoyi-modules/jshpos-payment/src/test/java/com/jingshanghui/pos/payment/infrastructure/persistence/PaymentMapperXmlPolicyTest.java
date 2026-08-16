package com.jingshanghui.pos.payment.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 复杂资金 SQL 位于 XML，并对每个运行时语句显式携带可信租户条件。 */
class PaymentMapperXmlPolicyTest {

    @Test
    void everyStatementIsExplicitTenantScopedAndAvoidsSelectStar() throws IOException {
        String xml;
        try (var stream = getClass().getResourceAsStream("/mapper/payment/PaymentMapper.xml")) {
            assertThat(stream).isNotNull();
            xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(xml.toLowerCase()).doesNotContain("select *");
        Pattern statement = Pattern.compile("<(?:select|insert|update|delete)\\b[^>]*>(.*?)</(?:select|insert|update|delete)>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        var matcher = statement.matcher(xml);
        int count = 0;
        while (matcher.find()) {
            count++;
            assertThat(matcher.group(1)).as("statement %s must carry tenantId", count).contains("#{tenantId}");
        }
        assertThat(count).isGreaterThanOrEqualTo(30);
        assertThat(xml).contains("FOR UPDATE", "record_version=record_version+1", "resultMap");
    }
}
