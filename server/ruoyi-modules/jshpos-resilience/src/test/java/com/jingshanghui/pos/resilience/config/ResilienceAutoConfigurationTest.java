package com.jingshanghui.pos.resilience.config;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.resilience.infrastructure.synthetic.FileSystemSyntheticRestoreTarget;
import com.jingshanghui.pos.resilience.infrastructure.synthetic.InternalSyntheticBackupSource;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 内部合成恢复必须显式双重确认、绑定可信租户并在生产 profile 失败关闭。 */
class ResilienceAutoConfigurationTest {
    @TempDir
    Path root;

    @Test
    void defaultModeKeepsDangerousAdaptersUnavailable() {
        var configuration = new ResilienceAutoConfiguration();
        var environment = new MockEnvironment();
        var tenantContext = mock(TrustedTenantContext.class);

        assertThatThrownBy(() -> configuration.backupSource(environment)
            .capture(java.util.Set.of("TENANT_A"), java.time.Instant.now()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("未配置");
        assertThatThrownBy(() -> configuration.authorizedBackupScope(environment, tenantContext).tenantIds())
            .isInstanceOf(ServiceException.class).hasMessageContaining("未配置");
        assertThatThrownBy(() -> configuration.isolatedRestoreTarget(environment)
            .beginEmpty("01M0X8R0000000000000000004", java.util.Set.of("TENANT_A")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("未配置");

        assertThatThrownBy(() -> configuration.backupObjectStore(environment)
            .putNew("backup/object", new byte[]{1}, Instant.now()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("未配置");
        assertThatThrownBy(() -> configuration.backupObjectStore(environment).get("backup/object"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("未配置");

        var restore = configuration.isolatedRestoreTarget(environment);
        assertThatThrownBy(() -> restore.restore(
            com.jingshanghui.pos.resilience.domain.BackupModels.DataClass.CONFIG,
            "config.synthetic", new byte[]{1}))
            .isInstanceOf(ServiceException.class).hasMessageContaining("未配置");
        assertThatThrownBy(() -> restore.validateAndReconcile("R4", Instant.now()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("未配置");
        assertThatThrownBy(restore::complete)
            .isInstanceOf(ServiceException.class).hasMessageContaining("未配置");
        restore.abort();
    }

    @Test
    void fullyConfirmedInternalModeUsesTrustedScopeAndFileAdapters() {
        var configuration = new ResilienceAutoConfiguration();
        var environment = enabledEnvironment();
        var tenantContext = mock(TrustedTenantContext.class);
        when(tenantContext.requireTenantId()).thenReturn("TENANT_A");

        assertThat(configuration.backupSource(environment)).isInstanceOf(InternalSyntheticBackupSource.class);
        assertThat(configuration.isolatedRestoreTarget(environment)).isInstanceOf(FileSystemSyntheticRestoreTarget.class);
        assertThat(configuration.authorizedBackupScope(environment, tenantContext).tenantIds())
            .containsExactly("TENANT_A");
    }

    @Test
    void partialConfirmationAndProductionProfileFailStartup() {
        var configuration = new ResilienceAutoConfiguration();
        var partial = new MockEnvironment().withProperty("JSH_RESILIENCE_EVIDENCE_LEVEL", "SYNTHETIC_RESTORE");
        assertThatThrownBy(() -> configuration.backupSource(partial))
            .isInstanceOf(ServiceException.class).hasMessageContaining("双重确认");

        var production = enabledEnvironment();
        production.setActiveProfiles("production");
        assertThatThrownBy(() -> configuration.backupSource(production))
            .isInstanceOf(ServiceException.class).hasMessageContaining("生产 profile");

        var productionPrefix = enabledEnvironment();
        productionPrefix.setActiveProfiles("PROD-cn");
        assertThatThrownBy(() -> configuration.isolatedRestoreTarget(productionPrefix))
            .isInstanceOf(ServiceException.class).hasMessageContaining("生产 profile");

        var missingRoot = new MockEnvironment()
            .withProperty("JSH_RESILIENCE_EVIDENCE_LEVEL", "SYNTHETIC_RESTORE")
            .withProperty("JSH_INTERNAL_SYNTHETIC_RESILIENCE_ACK", "INTERNAL_ONLY_NOT_PRODUCTION");
        assertThatThrownBy(() -> configuration.isolatedRestoreTarget(missingRoot))
            .isInstanceOf(ServiceException.class).hasMessageContaining("根目录未配置");
    }

    @Test
    void objectStoreCipherAndKeyProviderUseExplicitRuntimeConfiguration() throws Exception {
        var configuration = new ResilienceAutoConfiguration();
        var objectRoot = root.resolve("objects");
        var objectEnvironment = new MockEnvironment()
            .withProperty("JSH_BACKUP_OBJECT_ROOT", objectRoot.toString());
        var store = configuration.backupObjectStore(objectEnvironment);
        String objectKey = "backups/" + "a".repeat(64) + "/01M0X8R0000000000000000010/" + "b".repeat(64) + ".aead";
        store.putNew(objectKey, "payload".getBytes(StandardCharsets.UTF_8),
            Instant.parse("2027-08-26T00:00:00Z"));
        assertThat(store.get(objectKey)).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.exists(objectRoot.resolve(objectKey))).isTrue();

        byte[] keyBytes = new byte[32];
        java.util.Arrays.fill(keyBytes, (byte) 7);
        var keyEnvironment = new MockEnvironment()
            .withProperty("JSH_BACKUP_KEY_VERSION", "R4-K1")
            .withProperty("JSH_BACKUP_KEY_B64", Base64.getEncoder().encodeToString(keyBytes));
        assertThat(configuration.backupKeyProvider(keyEnvironment).resolve("R4-K1").getEncoded())
            .isEqualTo(keyBytes);
        assertThat(configuration.aesGcmBackupCipher()).isNotNull();

        assertThatThrownBy(() -> configuration.backupKeyProvider(keyEnvironment).resolve("R4-K2"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("密钥版本不可用");
        assertThatThrownBy(() -> configuration.backupKeyProvider(new MockEnvironment()
            .withProperty("JSH_BACKUP_KEY_VERSION", "R4-K1")
            .withProperty("JSH_BACKUP_KEY_B64", "not-base64")).resolve("R4-K1"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("密钥版本不可用");
        assertThatThrownBy(() -> configuration.backupKeyProvider(new MockEnvironment()
            .withProperty("JSH_BACKUP_KEY_VERSION", "R4-K1")
            .withProperty("JSH_BACKUP_KEY_B64", Base64.getEncoder().encodeToString(new byte[16])))
            .resolve("R4-K1"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("密钥版本不可用");
    }

    private MockEnvironment enabledEnvironment() {
        return new MockEnvironment()
            .withProperty("JSH_RESILIENCE_EVIDENCE_LEVEL", "SYNTHETIC_RESTORE")
            .withProperty("JSH_INTERNAL_SYNTHETIC_RESILIENCE_ACK", "INTERNAL_ONLY_NOT_PRODUCTION")
            .withProperty("JSH_SYNTHETIC_RESTORE_ROOT", root.toString());
    }
}
