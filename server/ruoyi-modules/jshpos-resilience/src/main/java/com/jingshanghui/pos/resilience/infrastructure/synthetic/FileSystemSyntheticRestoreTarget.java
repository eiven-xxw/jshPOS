package com.jingshanghui.pos.resilience.infrastructure.synthetic;

import com.jingshanghui.pos.resilience.application.port.BackupPorts.RestoreTarget;
import com.jingshanghui.pos.resilience.domain.BackupModels.DataClass;
import com.jingshanghui.pos.resilience.domain.BackupModels.Reconciliation;
import com.jingshanghui.pos.resilience.domain.BackupRules;
import org.dromara.common.core.exception.ServiceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 内部合成恢复的空文件目标；每个 drill 使用独立目录并只追加写入。
 * 失败目标保留 ABORTED 标记，不删除或覆盖证据。
 */
public final class FileSystemSyntheticRestoreTarget implements RestoreTarget {
    private static final Pattern DRILL = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern LOGICAL = Pattern.compile("^[A-Za-z0-9._/-]{1,256}$");
    private static final String PREFIX = "JSH_SYNTHETIC_RESTORE_V1";

    private final Path root;
    private final Map<DataClass, Path> restored = new EnumMap<>(DataClass.class);
    private Path activeDirectory;
    private String tenantScopeSha256;
    private boolean validated;

    public FileSystemSyntheticRestoreTarget(Path root) {
        if (root == null) throw failure("BAK-SYN-002: 合成恢复根目录缺失", 500);
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public synchronized void beginEmpty(String drillId, Set<String> tenantIds) {
        if (activeDirectory != null || drillId == null || !DRILL.matcher(drillId).matches()
            || tenantIds == null || tenantIds.isEmpty()) {
            throw failure("BAK-SYN-003: 合成恢复目标身份非法或已有执行", 409);
        }
        Path target = root.resolve(drillId).normalize();
        if (!target.startsWith(root)) throw failure("BAK-SYN-004: 合成恢复目标越过受控根目录", 403);
        try {
            Files.createDirectories(root);
            Files.createDirectory(target);
        } catch (IOException exception) {
            throw failure("BAK-SYN-005: 合成恢复目标不是空目录", 409);
        }
        activeDirectory = target;
        tenantScopeSha256 = BackupRules.tenantScopeSha256(tenantIds);
        restored.clear();
        validated = false;
    }

    @Override
    public synchronized void restore(DataClass dataClass, String logicalName, byte[] plaintext) {
        requireActive();
        if (dataClass == null || logicalName == null || !LOGICAL.matcher(logicalName).matches()
            || logicalName.contains("..") || logicalName.contains("\\") || plaintext == null
            || plaintext.length == 0) {
            throw failure("BAK-SYN-006: 合成恢复对象类型、路径或内容非法", 400);
        }
        Path target = activeDirectory.resolve(logicalName).normalize();
        if (!target.startsWith(activeDirectory)) throw failure("BAK-SYN-007: 合成恢复对象路径越界", 403);
        if (restored.putIfAbsent(dataClass, target) != null) {
            throw failure("BAK-SYN-008: 合成恢复数据类别重复", 409);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, plaintext, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            restored.remove(dataClass);
            throw failure("BAK-SYN-009: 合成恢复对象写入失败", 503);
        }
    }

    @Override
    public synchronized Reconciliation validateAndReconcile(String schemaVersion, Instant pointInTime) {
        requireActive();
        if (schemaVersion == null || schemaVersion.isBlank() || pointInTime == null) {
            throw failure("BAK-SYN-010: 合成恢复 Schema 或恢复点缺失", 400);
        }
        if (!restored.keySet().equals(EnumSet.allOf(DataClass.class))) {
            throw failure("BAK-SYN-011: 合成恢复未覆盖全部六类对象", 422);
        }
        for (Map.Entry<DataClass, Path> entry : restored.entrySet()) {
            String expected = String.join("|", PREFIX, entry.getKey().name(), tenantScopeSha256,
                pointInTime.toString());
            try {
                String actual = Files.readString(entry.getValue(), StandardCharsets.UTF_8);
                if (!expected.equals(actual)) throw failure("BAK-SYN-012: 合成恢复对象范围或恢复点不一致", 422);
            } catch (IOException exception) {
                throw failure("BAK-SYN-013: 合成恢复对象不可读", 422);
            }
        }
        validated = true;
        return new Reconciliation(true, true, 0, 0, 0, 0);
    }

    @Override
    public synchronized void complete() {
        requireActive();
        if (!validated) throw failure("BAK-SYN-014: 未完成核对不得结束合成恢复", 409);
        marker(".COMPLETE", tenantScopeSha256);
        reset();
    }

    @Override
    public synchronized void abort() {
        if (activeDirectory != null) {
            marker(".ABORTED", tenantScopeSha256 == null ? "UNKNOWN" : tenantScopeSha256);
            reset();
        }
    }

    private void marker(String name, String content) {
        try {
            Files.writeString(activeDirectory.resolve(name), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            throw failure("BAK-SYN-015: 合成恢复状态标记写入失败", 503);
        }
    }

    private void requireActive() {
        if (activeDirectory == null) throw failure("BAK-SYN-016: 合成恢复目标尚未开始", 409);
    }

    private void reset() {
        activeDirectory = null;
        tenantScopeSha256 = null;
        restored.clear();
        validated = false;
    }

    private static ServiceException failure(String message, int code) {
        return new ServiceException(message, code);
    }
}
