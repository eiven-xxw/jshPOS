package com.jingshanghui.pos.resilience.config;

import com.jingshanghui.pos.resilience.application.port.BackupPorts.*;
import com.jingshanghui.pos.resilience.domain.BackupModels.*;
import com.jingshanghui.pos.resilience.infrastructure.security.AesGcmBackupCipher;
import com.jingshanghui.pos.resilience.infrastructure.storage.FileSystemImmutableBackupObjectStore;
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
    public Source backupSource() { return (tenants, cutoff) -> { unavailable(); return java.util.List.of(); }; }

    @Bean @ConditionalOnMissingBean(AuthorizedScope.class)
    public AuthorizedScope authorizedBackupScope() { return () -> { unavailable(); return Set.of(); }; }

    @Bean @ConditionalOnMissingBean(RestoreTarget.class)
    public RestoreTarget isolatedRestoreTarget() {
        return new RestoreTarget() {
            @Override public void beginEmpty(String drillId, Set<String> tenants) { unavailable(); }
            @Override public void restore(DataClass type, String name, byte[] content) { unavailable(); }
            @Override public Reconciliation validateAndReconcile(String schema, java.time.Instant point) { unavailable(); return null; }
            @Override public void complete() { unavailable(); }
            @Override public void abort() { }
        };
    }

    private static <T> T missingKey() { throw new ServiceException("BAK-CFG-001: 独立备份密钥版本不可用", 503); }
    private static void unavailable() { throw new ServiceException("BAK-CFG-002: 备份源或空隔离恢复适配器未配置", 503); }
}
