package org.dromara.web.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** T2-SEC-002 生产缺失 Secret、默认凭据、管理端点和可选集成启动失败回归。 */
@Tag("dev")
class ProductionSecurityConfigurationTest {

    @Test
    void shouldStartWithCompleteControlledProductionConfiguration() {
        try (AnnotationConfigApplicationContext context = context(validProperties())) {
            assertNotNull(context.getBean(ProductionSecurityConfiguration.ProductionSecurityGate.class));
        }
    }

    @Test
    void shouldFailStartupWhenRequiredSecretIsMissing() {
        Map<String, Object> properties = validProperties();
        properties.remove("spring.data.redis.password");
        assertStartupRejected(properties, "spring.data.redis.password");
    }

    @Test
    void shouldFailStartupWhenDefaultDatabaseCredentialReturns() {
        Map<String, Object> properties = validProperties();
        properties.put("spring.datasource.dynamic.datasource.master.username", "root");
        assertStartupRejected(properties, "root");
    }

    @Test
    void shouldFailStartupWhenActuatorExposureIsExpanded() {
        Map<String, Object> properties = validProperties();
        properties.put("management.endpoints.web.exposure.include", "*");
        assertStartupRejected(properties, "Actuator");
    }

    @Test
    void shouldFailStartupWhenOptionalIntegrationIsEnabledWithoutSecret() {
        Map<String, Object> properties = validProperties();
        properties.put("snail-job.enabled", "true");
        properties.put("snail-job.token", "");
        assertStartupRejected(properties, "snail-job.token");
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("prod");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("gate8c", properties));
        context.register(ProductionSecurityConfiguration.class);
        context.refresh();
        return context;
    }

    private static void assertStartupRejected(Map<String, Object> properties, String marker) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        try (context) {
            context.getEnvironment().setActiveProfiles("prod");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("gate8c", properties));
            context.register(ProductionSecurityConfiguration.class);
            BeanCreationException error = assertThrows(BeanCreationException.class, context::refresh);
            assertTrue(rootMessage(error).contains(marker));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return String.valueOf(current.getMessage());
    }

    private static Map<String, Object> validProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.dynamic.datasource.master.url", "jdbc:mysql://db.internal/jshpos");
        properties.put("spring.datasource.dynamic.datasource.master.username", "jshpos_app");
        properties.put("spring.datasource.dynamic.datasource.master.password", "controlled-db-secret-2026");
        properties.put("spring.data.redis.password", "controlled-redis-secret-2026");
        properties.put("sa-token.jwt-secret-key", "controlled-jwt-secret-value-2026-rotate");
        properties.put("api-decrypt.publicKey", "P".repeat(96));
        properties.put("api-decrypt.privateKey", "K".repeat(96));
        properties.put("spring.boot.admin.client.username", "actuator_reader");
        properties.put("spring.boot.admin.client.password", "controlled-actuator-secret-2026");
        properties.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        properties.put("management.endpoint.health.show-details", "when_authorized");
        properties.put("springdoc.api-docs.enabled", "false");
        properties.put("spring.boot.admin.client.enabled", "false");
        properties.put("snail-job.enabled", "false");
        properties.put("mail.enabled", "false");
        return properties;
    }
}
