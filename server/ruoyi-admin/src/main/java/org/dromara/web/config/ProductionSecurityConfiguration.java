package org.dromara.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Set;

/**
 * 生产安全配置启动门禁。
 *
 * <p>该门禁在业务端口开放前验证必需 Secret、可选集成和 Actuator 暴露面；
 * 配置缺失或被命令行参数扩大时失败关闭，不输出任何 Secret 值。</p>
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionSecurityConfiguration {

    private static final Set<String> FORBIDDEN_VALUES = Set.of(
        "root", "123456", "ruoyi123", "abcdefghijklmnopqrstuvwxyz", "password", "changeme"
    );
    private static final Set<String> MANAGEMENT_ALLOWLIST = Set.of("health", "info", "prometheus");

    /** 创建成功即表示当前生产配置已通过最小安全门禁。 */
    @Bean
    ProductionSecurityGate productionSecurityGate(Environment environment) {
        requireIdentifier(environment, "spring.datasource.dynamic.datasource.master.url");
        String databaseUser = requireIdentifier(environment,
            "spring.datasource.dynamic.datasource.master.username");
        if ("root".equalsIgnoreCase(databaseUser)) {
            fail("生产数据库禁止使用 root 账号");
        }
        requireSecret(environment, "spring.datasource.dynamic.datasource.master.password", 12);
        requireSecret(environment, "spring.data.redis.password", 16);
        requireSecret(environment, "sa-token.jwt-secret-key", 32);
        requireSecret(environment, "api-decrypt.publicKey", 64);
        requireSecret(environment, "api-decrypt.privateKey", 64);
        requireIdentifier(environment, "spring.boot.admin.client.username");
        requireSecret(environment, "spring.boot.admin.client.password", 16);

        validateManagementEndpoints(environment);
        validateOptionalIntegrations(environment);
        return new ProductionSecurityGate();
    }

    private static void validateManagementEndpoints(Environment environment) {
        String raw = requireIdentifier(environment, "management.endpoints.web.exposure.include");
        Set<String> endpoints = Set.of(raw.toLowerCase(Locale.ROOT).replace(" ", "").split(","));
        if (!MANAGEMENT_ALLOWLIST.equals(endpoints)) {
            fail("Actuator 暴露端点必须严格等于 health,info,prometheus");
        }
        String details = requireIdentifier(environment, "management.endpoint.health.show-details");
        if (!"when_authorized".equals(details.toLowerCase(Locale.ROOT).replace('-', '_'))) {
            fail("健康详情必须按授权显示");
        }
        if (environment.getProperty("springdoc.api-docs.enabled", Boolean.class, true)) {
            fail("生产接口文档必须默认关闭");
        }
    }

    private static void validateOptionalIntegrations(Environment environment) {
        if (environment.getProperty("spring.boot.admin.client.enabled", Boolean.class, false)) {
            requireIdentifier(environment, "spring.boot.admin.client.url");
        }
        if (environment.getProperty("mail.enabled", Boolean.class, false)) {
            requireIdentifier(environment, "mail.host");
            requireIdentifier(environment, "mail.user");
            requireSecret(environment, "mail.pass", 16);
        }
    }

    private static String requireIdentifier(Environment environment, String key) {
        String value = normalized(environment.getProperty(key));
        if (value.isEmpty() || unresolved(value)) {
            fail("缺少必需生产配置 " + key);
        }
        return value;
    }

    private static void requireSecret(Environment environment, String key, int minimumLength) {
        String value = normalized(environment.getProperty(key));
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.length() < minimumLength || unresolved(value) || FORBIDDEN_VALUES.contains(lower)
            || lower.contains("您的") || lower.contains("xxxx") || lower.contains("********")) {
            fail("生产 Secret 缺失、强度不足或仍使用默认值：" + key);
        }
    }

    private static boolean unresolved(String value) {
        return value.contains("${") || value.contains("@monitor.");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static void fail(String message) {
        throw new IllegalStateException("SEC-PROD-001: " + message);
    }

    /** 只读启动证明，不承载或暴露任何凭据。 */
    static final class ProductionSecurityGate { }
}
