package org.dromara.web.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 生产 YAML 不得回归静态凭据、全量 Actuator 或默认启用外部集成。 */
@Tag("dev")
class ProductionConfigurationContractTest {

    @Test
    void shouldContainOnlyControlledSecretReferencesAndSafeManagementDefaults() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application-prod.yml"));
        assertAll(
            () -> assertFalse(yaml.contains("password: root")),
            () -> assertFalse(yaml.contains("password: ruoyi123")),
            () -> assertFalse(yaml.contains("SJ_cKqBTP")),
            () -> assertFalse(yaml.contains("@monitor.password@")),
            () -> assertFalse(yaml.contains("include: '*'")),
            () -> assertFalse(yaml.toLowerCase().contains("show-details: always")),
            () -> assertTrue(yaml.contains("password: ${JSH_DB_PASSWORD}")),
            () -> assertTrue(yaml.contains("password: ${JSH_REDIS_PASSWORD}")),
            () -> assertTrue(yaml.contains("jwt-secret-key: ${JSH_JWT_SECRET}")),
            () -> assertTrue(yaml.contains("enabled: ${JSH_BOOT_ADMIN_ENABLED:false}")),
            () -> assertTrue(yaml.contains("enabled: ${JSH_SNAIL_JOB_ENABLED:false}")),
            () -> assertTrue(yaml.contains("include: health,info,prometheus")),
            () -> assertTrue(yaml.contains("show-details: when_authorized"))
        );
    }
}
