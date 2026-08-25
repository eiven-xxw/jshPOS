package com.jingshanghui.pos.resilience.config;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.resilience.application.port.BackupPorts.*;
import com.jingshanghui.pos.resilience.domain.BackupModels.*;
import com.jingshanghui.pos.resilience.infrastructure.security.AesGcmBackupCipher;
import com.jingshanghui.pos.resilience.infrastructure.storage.FileSystemImmutableBackupObjectStore;
import com.jingshanghui.pos.resilience.infrastructure.synthetic.FileSystemSyntheticRestoreTarget;
import com.jingshanghui.pos.resilience.infrastructure.synthetic.InternalSyntheticBackupSource;
import org.dromara.common.core.exception.ServiceException;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/** Gate 6A 恢复模块入口；缺少独立密钥、源或隔离目标时所有危险动作失败关闭。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.resilience")
@MapperScan("com.jingshanghui.pos.resilience.infrastructure.persistence.mapper")
public class ResilienceAutoConfiguration {
    @Bean public AesGcmBackupCipher aesGcmBackupCipher() { return new AesGcmBackupCipher(); }

    @Bean @ConditionalOnMissingBean(ObjectStore.class)
    public ObjectStore backupObjectStore(Environment environment) {
        String root = environment.getProperty("JSH_BACKUP_OBJECT_ROOT");
        if (root != null && !root.isBlank()) return new FileSystemImmutableBackupObjectStore(Path.of(root));
        return new ObjectStore() {
            @Override public void putNew(String key, byte[] content, java.time.Instant until) { unavailable(); }
            @Override public byte[] get(String key) { unavailable(); return new byte[0]; }
        };
    }

    @Bean @ConditionalOnMissingBean(KeyProvider.class)
    public KeyProvider backupKeyProvider(Environment environment) {
        String version = environment.getProperty("JSH_BACKUP_KEY_VERSION");
        String encoded = environment.getProperty("JSH_BACKUP_KEY_B64");
        return requested -> {
            if (version == null || encoded == null || !version.equals(requested)) return missingKey();
            try {
                byte[] key = Base64.getDecoder().decode(encoded);
                if (key.length != 32) return missingKey();
                return new SecretKeySpec(key, "AES");
            } catch (IllegalArgumentException exception) { return missingKey(); }
        };
    }

    @Bean @ConditionalOnMissingBean(Source.class)
    public Source backupSource(Environment environment) {
        if (internalSyntheticEnabled(environment)) return new InternalSyntheticBackupSource();
        return (tenants, cutoff) -> { unavailable(); return java.util.List.of(); };
    }

    @Bean @ConditionalOnMissingBean(AuthorizedScope.class)
    public AuthorizedScope authorizedBackupScope(Environment environment, TrustedTenantContext tenantContext) {
        if (internalSyntheticEnabled(environment)) return () -> Set.of(tenantContext.requireTenantId());
        return () -> { unavailable(); return Set.of(); };
    }

    @Bean @ConditionalOnMissingBean(RestoreTarget.class)
    public RestoreTarget isolatedRestoreTarget(Environment environment) {
        if (internalSyntheticEnabled(environment)) {
            String root = environment.getProperty("JSH_SYNTHETIC_RESTORE_ROOT");
            if (root == null || root.isBlank()) {
                throw new ServiceException("BAK-CFG-004: 合成恢复目标根目录未配置", 503);
            }
            return new FileSystemSyntheticRestoreTarget(Path.of(root));
        }
        return new RestoreTarget() {
            @Override public void beginEmpty(String drillId, Set<String> tenants) { unavailable(); }
            @Override public void restore(DataClass type, String name, byte[] content) { unavailable(); }
            @Override public Reconciliation validateAndReconcile(String schema, java.time.Instant point) { unavailable(); return null; }
            @Override public void complete() { unavailable(); }
            @Override public void abort() { }
        };
    }

    /** 内部合成适配器必须双重显式确认，且任何生产 profile 下均拒绝启动。 */
    private static boolean internalSyntheticEnabled(Environment environment) {
        String level = environment.getProperty("JSH_RESILIENCE_EVIDENCE_LEVEL", "");
        String acknowledgement = environment.getProperty("JSH_INTERNAL_SYNTHETIC_RESILIENCE_ACK", "");
        boolean requested = !level.isBlank() || !acknowledgement.isBlank();
        boolean enabled = "SYNTHETIC_RESTORE".equals(level)
            && "INTERNAL_ONLY_NOT_PRODUCTION".equals(acknowledgement);
        if (requested && !enabled) {
            throw new ServiceException("BAK-CFG-003: 内部合成恢复双重确认不完整", 503);
        }
        if (enabled) {
            for (String profile : environment.getActiveProfiles()) {
                String normalized = profile.toLowerCase(Locale.ROOT);
                if (normalized.equals("prod") || normalized.equals("production") || normalized.startsWith("prod-")) {
                    throw new ServiceException("BAK-CFG-005: 生产 profile 禁止合成恢复适配器", 503);
                }
            }
        }
        return enabled;
    }

    private static <T> T missingKey() { throw new ServiceException("BAK-CFG-001: 独立备份密钥版本不可用", 503); }
    private static void unavailable() { throw new ServiceException("BAK-CFG-002: 备份源或空隔离恢复适配器未配置", 503); }
}
