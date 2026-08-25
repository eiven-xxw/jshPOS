package com.jingshanghui.pos.resilience.infrastructure.synthetic;

import com.jingshanghui.pos.resilience.domain.BackupModels.DataClass;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 正式 JAR 内部合成恢复适配器的文件隔离、完整类别和失败关闭回归。 */
class InternalSyntheticResilienceAdaptersTest {
    private static final Set<String> TENANTS = Set.of("SYNTHETIC_A", "SYNTHETIC_B");
    private static final Instant POINT = Instant.parse("2026-08-26T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void sixSyntheticClassesRestoreIntoAnEmptyFileTarget() {
        var source = new InternalSyntheticBackupSource();
        var target = new FileSystemSyntheticRestoreTarget(root);
        var objects = source.capture(TENANTS, POINT);

        assertThat(objects).hasSize(6);
        assertThat(objects).extracting(value -> value.dataClass())
            .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(DataClass.class));

        target.beginEmpty("01M0X8R0000000000000000001", TENANTS);
        objects.forEach(value -> target.restore(value.dataClass(), value.logicalName(), value.content()));
        var result = target.validateAndReconcile("R4", POINT);

        assertThat(result.flywayValidated()).isTrue();
        assertThat(result.projectionRebuilt()).isTrue();
        assertThat(result.tenantDifferences() + result.businessDayDifferences()
            + result.cursorDifferences() + result.auditDifferences()).isZero();
        target.complete();
    }

    @Test
    void missingClassAndPathTraversalFailClosed() {
        var source = new InternalSyntheticBackupSource();
        var missingTarget = new FileSystemSyntheticRestoreTarget(root.resolve("missing"));
        var objects = source.capture(TENANTS, POINT);
        missingTarget.beginEmpty("01M0X8R0000000000000000002", TENANTS);
        objects.stream().limit(5)
            .forEach(value -> missingTarget.restore(value.dataClass(), value.logicalName(), value.content()));
        assertThatThrownBy(() -> missingTarget.validateAndReconcile("R4", POINT))
            .isInstanceOf(ServiceException.class).hasMessageContaining("六类");
        missingTarget.abort();

        var traversalTarget = new FileSystemSyntheticRestoreTarget(root.resolve("traversal"));
        traversalTarget.beginEmpty("01M0X8R0000000000000000003", TENANTS);
        assertThatThrownBy(() -> traversalTarget.restore(DataClass.CONFIG, "../secret", new byte[]{1}))
            .isInstanceOf(ServiceException.class).hasMessageContaining("路径");
    }

    @Test
    void invalidSourceAndRestoreIdentitiesFailClosed() {
        var source = new InternalSyntheticBackupSource();
        assertThatThrownBy(() -> source.capture(null, POINT)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> source.capture(Set.of(), POINT)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> source.capture(TENANTS, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> new FileSystemSyntheticRestoreTarget(null))
            .isInstanceOf(ServiceException.class).hasMessageContaining("根目录缺失");

        var target = new FileSystemSyntheticRestoreTarget(root.resolve("invalid"));
        assertThatThrownBy(() -> target.complete())
            .isInstanceOf(ServiceException.class).hasMessageContaining("尚未开始");
        assertThatThrownBy(() -> target.beginEmpty(null, TENANTS)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> target.beginEmpty("invalid", TENANTS)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> target.beginEmpty("01M0X8R0000000000000000005", Set.of()))
            .isInstanceOf(ServiceException.class);

        target.beginEmpty("01M0X8R0000000000000000006", TENANTS);
        assertThatThrownBy(() -> target.beginEmpty("01M0X8R0000000000000000007", TENANTS))
            .isInstanceOf(ServiceException.class).hasMessageContaining("已有执行");
        assertThatThrownBy(() -> target.restore(null, "config.synthetic", new byte[]{1}))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> target.restore(DataClass.CONFIG, "config.synthetic", null))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> target.restore(DataClass.CONFIG, "config\\secret", new byte[]{1}))
            .isInstanceOf(ServiceException.class);
        target.abort();
        target.abort();
    }

    @Test
    void duplicateTamperedAndIncompleteRestoreCannotBeCompleted() throws Exception {
        var source = new InternalSyntheticBackupSource();
        var objects = source.capture(TENANTS, POINT);

        var duplicateTarget = new FileSystemSyntheticRestoreTarget(root.resolve("duplicate"));
        duplicateTarget.beginEmpty("01M0X8R0000000000000000008", TENANTS);
        var first = objects.get(0);
        duplicateTarget.restore(first.dataClass(), first.logicalName(), first.content());
        assertThatThrownBy(() -> duplicateTarget.restore(first.dataClass(), "second.synthetic", first.content()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("类别重复");
        assertThatThrownBy(duplicateTarget::complete)
            .isInstanceOf(ServiceException.class).hasMessageContaining("未完成核对");
        duplicateTarget.abort();

        var tamperedRoot = root.resolve("tampered");
        var tamperedTarget = new FileSystemSyntheticRestoreTarget(tamperedRoot);
        String drill = "01M0X8R0000000000000000009";
        tamperedTarget.beginEmpty(drill, TENANTS);
        objects.forEach(value -> tamperedTarget.restore(value.dataClass(), value.logicalName(), value.content()));
        Files.writeString(tamperedRoot.resolve(drill).resolve(first.logicalName()), "tampered",
            StandardCharsets.UTF_8);
        assertThatThrownBy(() -> tamperedTarget.validateAndReconcile("R4", POINT))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不一致");
        tamperedTarget.abort();
    }

    @Test
    void occupiedTargetWriteFailureAndUnreadableObjectFailClosed() throws Exception {
        String occupiedDrill = "01M0X8R0000000000000000011";
        Path occupiedRoot = root.resolve("occupied");
        Files.createDirectories(occupiedRoot.resolve(occupiedDrill));
        var occupiedTarget = new FileSystemSyntheticRestoreTarget(occupiedRoot);
        assertThatThrownBy(() -> occupiedTarget.beginEmpty(occupiedDrill, TENANTS))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不是空目录");

        String writeDrill = "01M0X8R0000000000000000012";
        Path writeRoot = root.resolve("write-failure");
        var writeTarget = new FileSystemSyntheticRestoreTarget(writeRoot);
        writeTarget.beginEmpty(writeDrill, TENANTS);
        Files.writeString(writeRoot.resolve(writeDrill).resolve("blocked"), "file", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> writeTarget.restore(DataClass.CONFIG, "blocked/config.synthetic", new byte[]{1}))
            .isInstanceOf(ServiceException.class).hasMessageContaining("写入失败");
        writeTarget.abort();

        String unreadableDrill = "01M0X8R0000000000000000013";
        Path unreadableRoot = root.resolve("unreadable");
        var unreadableTarget = new FileSystemSyntheticRestoreTarget(unreadableRoot);
        var objects = new InternalSyntheticBackupSource().capture(TENANTS, POINT);
        unreadableTarget.beginEmpty(unreadableDrill, TENANTS);
        objects.forEach(value -> unreadableTarget.restore(value.dataClass(), value.logicalName(), value.content()));
        assertThatThrownBy(() -> unreadableTarget.validateAndReconcile(null, POINT))
            .isInstanceOf(ServiceException.class).hasMessageContaining("Schema");
        Files.delete(unreadableRoot.resolve(unreadableDrill).resolve(objects.get(0).logicalName()));
        assertThatThrownBy(() -> unreadableTarget.validateAndReconcile("R4", POINT))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不可读");
        unreadableTarget.abort();
    }
}
