package com.jingshanghui.pos.resilience.infrastructure.storage;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/** 本地合成对象存储的命名空间、只追加、保留与缺片回归。 */
class FileSystemImmutableBackupObjectStoreTest {
    @TempDir Path root;

    @Test void writesOnceReadsAndRejectsOverwriteTraversalMissingAndExpiredRetention() {
        var store = new FileSystemImmutableBackupObjectStore(root);
        String key = "backups/"+"a".repeat(64)+"/01K2A000000000000000000001/"+"b".repeat(64)+".aead";
        store.putNew(key, new byte[]{1,2,3}, Instant.now().plusSeconds(3600));
        assertThat(store.get(key)).containsExactly(1,2,3);
        assertThatThrownBy(() -> store.putNew(key, new byte[]{4}, Instant.now().plusSeconds(3600))).isInstanceOf(ServiceException.class).hasMessageContaining("已存在");
        assertThatThrownBy(() -> store.get("../secret")).isInstanceOf(ServiceException.class).hasMessageContaining("对象键");
        assertThatThrownBy(() -> store.get(null)).isInstanceOf(ServiceException.class).hasMessageContaining("对象键");
        assertThatThrownBy(() -> store.get("backups\\secret")).isInstanceOf(ServiceException.class).hasMessageContaining("对象键");
        String missing = "backups/"+"a".repeat(64)+"/01K2A000000000000000000001/"+"c".repeat(64)+".aead";
        assertThatThrownBy(() -> store.get(missing)).isInstanceOf(ServiceException.class).hasMessageContaining("缺失");
        assertThatThrownBy(() -> store.putNew(missing, new byte[]{1}, Instant.now().minusSeconds(1))).isInstanceOf(ServiceException.class).hasMessageContaining("保留");
        assertThatThrownBy(() -> store.putNew(missing, new byte[]{1}, null)).isInstanceOf(ServiceException.class).hasMessageContaining("保留");
    }

    @Test void failsClosedWhenTheStorageRootCannotCreateNamespacedDirectories() throws Exception {
        Path rootFile = root.resolve("occupied-root");
        Files.write(rootFile, new byte[]{1});
        var store = new FileSystemImmutableBackupObjectStore(rootFile);
        String key = "backups/"+"a".repeat(64)+"/01K2A000000000000000000001/"+"b".repeat(64)+".aead";
        assertThatThrownBy(() -> store.putNew(key, new byte[]{1}, Instant.now().plusSeconds(3600)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("写入失败");
    }
}
