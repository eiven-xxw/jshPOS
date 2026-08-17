package com.jingshanghui.pos.resilience.domain;

import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static com.jingshanghui.pos.resilience.domain.BackupModels.*;

/** 备份恢复状态、不变量、路径、范围和 RPO/RTO 规则。 */
public final class BackupRules {
    public static final long MAX_RPO_SECONDS = 900;
    public static final long MAX_RTO_SECONDS = 3600;
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern TENANT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$");
    private static final Pattern LOGICAL = Pattern.compile("^[a-z][a-z0-9_-]{1,31}/[A-Za-z0-9][A-Za-z0-9._/-]{0,223}$");

    private BackupRules() {
    }

    public static void validateCreate(CreateBackup command, Instant now) {
        requireUlid(command.backupId(), "BAK-VAL-001: backupId非法");
        requireUlid(command.correlationId(), "BAK-VAL-002: correlationId非法");
        requireToken(command.environment(), "BAK-VAL-003: 环境标识非法");
        requireToken(command.schemaVersion(), "BAK-VAL-004: Schema版本非法");
        requireToken(command.applicationVersion(), "BAK-VAL-005: 应用版本非法");
        requireToken(command.keyVersion(), "BAK-VAL-006: 密钥版本非法");
        validateTenants(command.tenantIds());
        if (command.requestedBy() <= 0 || command.pointInTime() == null || command.latestIncludedFactAt() == null
            || command.immutableUntil() == null) fail("BAK-VAL-007: 时间或操作者缺失", 400);
        if (command.pointInTime().isAfter(now.plusSeconds(300))
            || command.latestIncludedFactAt().isAfter(command.pointInTime())) {
            fail("BAK-VAL-008: 恢复点或最后事实时间非法", 400);
        }
        if (!command.immutableUntil().isAfter(command.pointInTime())) {
            fail("BAK-VAL-009: 不可变保留期必须晚于恢复点", 400);
        }
    }

    public static void validateSourceObjects(Set<String> expectedTenants, List<SourceObject> objects) {
        if (objects == null || objects.isEmpty() || objects.size() > 10_000) {
            fail("BAK-VAL-010: 备份对象数量非法", 400);
        }
        EnumSet<DataClass> seen = EnumSet.noneOf(DataClass.class);
        Set<String> names = new HashSet<>();
        long total = 0;
        for (SourceObject object : objects) {
            if (object == null || object.dataClass() == null || object.content().length == 0
                || object.content().length > 64L * 1024 * 1024) fail("BAK-VAL-011: 对象为空或超限", 400);
            if (!LOGICAL.matcher(Objects.toString(object.logicalName(), "")).matches()
                || object.logicalName().contains("..") || object.logicalName().contains("\\")) {
                fail("BAK-VAL-012: 逻辑对象路径非法", 400);
            }
            requireToken(object.mediaType().replace('/', '_').replace('+', '_'), "BAK-VAL-013: 媒体类型非法");
            if (!object.tenantIds().equals(expectedTenants)) fail("BAK-SEC-001: 对象租户范围与恢复集合不一致", 403);
            if (!names.add(object.logicalName())) fail("BAK-VAL-014: 逻辑对象名重复", 409);
            seen.add(object.dataClass());
            total = Math.addExact(total, object.content().length);
        }
        if (!seen.containsAll(EnumSet.allOf(DataClass.class))) fail("BAK-VAL-015: 恢复集合数据类别不完整", 422);
        if (total > 512L * 1024 * 1024) fail("BAK-VAL-016: 单次合成备份总量超限", 413);
    }

    public static void validateRestore(RestoreBackup command, BackupSet backup) {
        requireUlid(command.drillId(), "BAK-VAL-017: drillId非法");
        requireUlid(command.backupId(), "BAK-VAL-018: backupId非法");
        requireUlid(command.correlationId(), "BAK-VAL-019: correlationId非法");
        validateTenants(command.expectedTenantIds());
        if (command.requestedBy() <= 0 || !"AVAILABLE".equals(backup.state())) fail("BAK-RST-001: 备份不可恢复", 409);
        if (!command.backupId().equals(backup.backupId())
            || !command.expectedTenantIds().equals(backup.tenantIds())) fail("BAK-SEC-002: 跨范围恢复被拒绝", 403);
        if (!Objects.equals(command.expectedSchemaVersion(), backup.schemaVersion())) fail("BAK-RST-002: Schema兼容窗口不匹配", 409);
        if (backup.objects().isEmpty()) fail("BAK-RST-003: 清单缺少对象", 422);
    }

    public static void requireSuccessfulReconciliation(Reconciliation result) {
        if (result == null || !result.flywayValidated() || !result.projectionRebuilt()
            || result.tenantDifferences() != 0 || result.businessDayDifferences() != 0
            || result.cursorDifferences() != 0 || result.auditDifferences() != 0) {
            fail("BAK-RST-004: 恢复守恒或游标审计校验失败", 422);
        }
    }

    public static long rpoSeconds(BackupSet backup) {
        long seconds = Duration.between(backup.latestIncludedFactAt(), backup.pointInTime()).getSeconds();
        if (seconds < 0) fail("BAK-RST-005: RPO时间轴非法", 422);
        return seconds;
    }

    public static long rtoSeconds(Instant started, Instant ended) {
        long millis = Duration.between(started, ended).toMillis();
        if (millis < 0) fail("BAK-RST-006: RTO时间轴非法", 422);
        return Math.max(1, (millis + 999) / 1000);
    }

    public static String tenantScopeSha256(Set<String> tenants) {
        validateTenants(tenants);
        return sha256(String.join("\n", new TreeSet<>(tenants)).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void validateTenants(Set<String> tenants) {
        if (tenants == null || tenants.isEmpty() || tenants.size() > 1_000
            || tenants.stream().anyMatch(value -> value == null || !TENANT.matcher(value).matches())) {
            fail("BAK-SEC-003: 租户范围非法", 403);
        }
    }

    private static void requireUlid(String value, String message) {
        if (value == null || !ULID.matcher(value).matches()) fail(message, 400);
    }

    private static void requireToken(String value, String message) {
        if (value == null || !TOKEN.matcher(value).matches()) fail(message, 400);
    }

    private static void fail(String message, int code) { throw new ServiceException(message, code); }
}
