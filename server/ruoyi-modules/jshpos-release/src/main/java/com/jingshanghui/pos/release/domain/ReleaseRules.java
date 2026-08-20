package com.jingshanghui.pos.release.domain;

import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.release.domain.ReleaseModels.*;
import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** 发布状态、兼容、营业保护、摘要与迁移失败关闭规则。 */
public final class ReleaseRules {
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9]+(?:\\.[0-9]+){0,3}(?:[-+][A-Za-z0-9.-]+)?$");
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern IDEMPOTENCY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$");

    private ReleaseRules() { }

    public static void validateCreate(CreateRelease command, String tenantId) {
        require(command != null && command.artifactType() != null && command.channel() != null,
            "UPG-INPUT-001: 发布类型和通道不能为空", 400);
        version(command.version(), "发布版本");
        sha(command.artifactSha256(), "发布物");
        sha(command.sbomSha256(), "SBOM");
        require(command.signatureBase64() != null && command.signatureBase64().length() >= 32
            && command.signatureBase64().length() <= 1024, "UPG-INPUT-002: 签名格式或长度无效", 400);
        requireText(command.keyVersion(), 64, "签名密钥版本");
        require(command.buildCommit() != null && command.buildCommit().matches("^[0-9a-f]{40}$"),
            "UPG-INPUT-003: 构建提交必须为完整SHA", 400);
        require(command.objectKey() != null && command.objectKey().startsWith("releases/" + tenantId + "/")
            && !command.objectKey().contains("..") && command.objectKey().length() <= 512,
            "UPG-SEC-001: 对象键未处于可信租户命名空间", 403);
        require(command.targetStoreIds() != null && !command.targetStoreIds().isEmpty()
            && command.targetStoreIds().size() <= 10_000 && command.targetStoreIds().stream().allMatch(id -> id != null && id > 0),
            "UPG-INPUT-004: 目标门店范围无效", 400);
        idempotency(command.idempotencyKey());
        validateCompatibility(command.compatibility());
    }

    public static String manifestSha(CreateRelease command, String tenantId) {
        CompatibilityWindow value = command.compatibility();
        Map<String, Object> compatibility = new TreeMap<>();
        compatibility.put("minAppVersion", value.minAppVersion());
        compatibility.put("maxAppVersion", value.maxAppVersion());
        compatibility.put("minProtocolVersion", value.minProtocolVersion());
        compatibility.put("maxProtocolVersion", value.maxProtocolVersion());
        compatibility.put("minSchemaVersion", value.minSchemaVersion());
        compatibility.put("maxSchemaVersion", value.maxSchemaVersion());
        compatibility.put("minSystemVersion", value.minSystemVersion());
        compatibility.put("maxSystemVersion", value.maxSystemVersion());
        compatibility.put("requiredCapabilitySha256", nullToEmpty(value.requiredCapabilitySha256()));
        Map<String, Object> manifest = new TreeMap<>();
        manifest.put("artifactSha256", command.artifactSha256());
        manifest.put("artifactType", command.artifactType().name());
        manifest.put("buildCommit", command.buildCommit());
        manifest.put("channel", command.channel().name());
        manifest.put("compatibility", compatibility);
        manifest.put("keyVersion", command.keyVersion());
        manifest.put("objectKey", command.objectKey());
        manifest.put("sbomSha256", command.sbomSha256());
        manifest.put("storeIds", new TreeSet<>(command.targetStoreIds()));
        manifest.put("tenantId", tenantId);
        manifest.put("version", command.version());
        return CanonicalJson.from(manifest).sha256();
    }

    public static void requireArtifact(Release release, ArtifactObservation observed) {
        require(observed != null && observed.sizeBytes() > 0, "UPG-ART-001: 发布物不可用", 422);
        require(release.artifactSha256().equals(observed.sha256()), "UPG-ART-002: 发布物摘要不匹配", 422);
        require(observed.signatureValid(), "UPG-ART-003: 发布物签名无效", 422);
        require(release.keyVersion().equals(observed.keyVersion()), "UPG-ART-004: 验签密钥版本不匹配", 422);
    }

    public static void requireTerminalReady(Release release, Rollout rollout, TrustedTerminal terminal,
                                            SafetySnapshot safety) {
        require(terminal != null && release.tenantId().equals(terminal.tenantId()), "UPG-SEC-002: 终端租户不可信", 403);
        require("ACTIVE".equals(terminal.status()), "UPG-TRM-001: 终端未激活或已吊销", 423);
        require(terminal.storeId() != null && release.targetStoreIds().contains(terminal.storeId())
            && rollout.targetStoreIds().contains(terminal.storeId()), "UPG-SEC-003: 终端不在受权门店范围", 403);
        CompatibilityWindow window = release.compatibility();
        inWindow(terminal.appVersion(), window.minAppVersion(), window.maxAppVersion(), "应用版本");
        inWindow(terminal.schemaVersion(), window.minSchemaVersion(), window.maxSchemaVersion(), "Schema版本");
        inWindow(terminal.minProtocolVersion(), window.minProtocolVersion(), window.maxProtocolVersion(), "协议版本");
        inWindow(terminal.systemVersion(), window.minSystemVersion(), window.maxSystemVersion(), "系统版本");
        if (!blank(window.requiredCapabilitySha256())) {
            require(window.requiredCapabilitySha256().equals(terminal.capabilitySha256()),
                "UPG-TRM-002: 终端能力快照不兼容", 409);
        }
        require(safety != null, "UPG-SAFE-001: 营业保护快照不可用", 503);
        require(safety.pendingOutboxCount() == 0, "UPG-SAFE-002: 存在待同步Outbox", 423);
        require(safety.unknownPaymentCount() == 0 && safety.unknownRefundCount() == 0,
            "UPG-SAFE-003: 存在UNKNOWN支付或退款", 423);
        require(!safety.openShift() && !safety.protectedBusinessWindow(), "UPG-SAFE-004: 营业时段或班次保护", 423);
        require(safety.storageHealthy() && safety.clockHealthy(), "UPG-SAFE-005: 存储或时钟健康检查失败", 423);
    }

    public static ReleaseState releaseTransition(ReleaseState from, ReleaseState to) {
        boolean allowed = (from == ReleaseState.DRAFT && to == ReleaseState.SIGNED)
            || (from == ReleaseState.SIGNED && to == ReleaseState.STAGED)
            || (from != ReleaseState.REVOKED && to == ReleaseState.REVOKED);
        require(allowed, "UPG-STATE-001: 非法发布物状态迁移", 409);
        return to;
    }

    public static RolloutState rolloutTransition(RolloutState from, RolloutState to, TaskSummary summary) {
        boolean allowed = (from == RolloutState.PLANNED && to == RolloutState.CANARY)
            || (from == RolloutState.CANARY && to == RolloutState.ROLLING && summary != null
                && summary.succeeded() > 0 && summary.active() == 0 && summary.failed() == 0)
            || (from == RolloutState.ROLLING && to == RolloutState.COMPLETED && summary != null
                && summary.succeeded() > 0 && summary.active() == 0 && summary.failed() == 0)
            || ((from == RolloutState.CANARY || from == RolloutState.ROLLING) && to == RolloutState.PAUSED)
            || (from == RolloutState.PAUSED && (to == RolloutState.CANARY || to == RolloutState.ROLLING))
            || ((from == RolloutState.CANARY || from == RolloutState.ROLLING || from == RolloutState.PAUSED)
                && to == RolloutState.FAILED);
        require(allowed, "UPG-STATE-002: 非法灰度状态迁移或健康门禁未通过", 409);
        return to;
    }

    public static TaskState taskTransition(ArtifactType type, TaskState from, ObservationType observation,
                                           String expectedSha, String observedSha) {
        require(observation != null, "UPG-INPUT-010: 软件执行观察类型不能为空", 400);
        if (observation == ObservationType.DIGEST_MISMATCH || !Objects.equals(expectedSha, observedSha)
            || observation == ObservationType.SIGNATURE_INVALID) return TaskState.FAILED_CLOSED;
        return switch (observation) {
            case DOWNLOAD_STARTED, DOWNLOAD_RESUMED -> requireTask(from, Set.of(TaskState.PLANNED, TaskState.DOWNLOADING), TaskState.DOWNLOADING);
            case ARTIFACT_VERIFIED -> requireTask(from, Set.of(TaskState.DOWNLOADING), TaskState.VERIFIED);
            case INSTALL_STARTED -> requireTask(from, Set.of(TaskState.VERIFIED), TaskState.INSTALLING);
            case INSTALL_SUCCEEDED -> requireTask(from, Set.of(TaskState.INSTALLING), TaskState.HEALTH_CHECK);
            case HEALTH_PASSED -> requireTask(from, Set.of(TaskState.HEALTH_CHECK), TaskState.SUCCEEDED);
            case HEALTH_FAILED -> requireTask(from, Set.of(TaskState.HEALTH_CHECK), schema(type)
                ? TaskState.FORWARD_FIX_REQUIRED : TaskState.ROLLED_BACK);
            case MIGRATION_FAILED, FORWARD_FIX_REQUIRED -> requireTask(from,
                Set.of(TaskState.INSTALLING, TaskState.HEALTH_CHECK), TaskState.FORWARD_FIX_REQUIRED);
            case ROLLBACK_SUCCEEDED -> requireTask(from, Set.of(TaskState.ROLLED_BACK), TaskState.ROLLED_BACK);
            default -> TaskState.FAILED_CLOSED;
        };
    }

    public static void validateRollout(CreateRollout command, Release release) {
        require(command != null && command.targetStoreIds() != null && !command.targetStoreIds().isEmpty()
            && release.targetStoreIds().containsAll(command.targetStoreIds()), "UPG-INPUT-005: 灰度范围越过发布范围", 403);
        require(command.canaryPercent() >= 1 && command.canaryPercent() <= 25,
            "UPG-INPUT-006: 首轮灰度比例必须为1至25", 400);
        idempotency(command.idempotencyKey());
    }

    public static String requestSha(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                byte[] encoded = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(encoded);
                digest.update((byte) ';');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public static void ulid(String value, String name) { require(value != null && ULID.matcher(value).matches(), "UPG-INPUT-007: " + name + "不是ULID", 400); }
    public static void idempotency(String value) { require(value != null && IDEMPOTENCY.matcher(value).matches(), "UPG-IDEMP-001: 幂等键无效", 400); }
    public static void sha(String value, String name) { require(value != null && SHA256.matcher(value).matches(), "UPG-INPUT-008: " + name + "摘要无效", 400); }

    private static void validateCompatibility(CompatibilityWindow value) {
        require(value != null, "UPG-COMP-001: 兼容窗口不能为空", 400);
        ordered(value.minAppVersion(), value.maxAppVersion(), "应用");
        ordered(value.minProtocolVersion(), value.maxProtocolVersion(), "协议");
        ordered(value.minSchemaVersion(), value.maxSchemaVersion(), "Schema");
        ordered(value.minSystemVersion(), value.maxSystemVersion(), "系统");
        if (!blank(value.requiredCapabilitySha256())) sha(value.requiredCapabilitySha256(), "能力快照");
    }
    private static void ordered(String min, String max, String name) {
        version(min, name + "最低版本"); version(max, name + "最高版本");
        require(compare(min, max) <= 0, "UPG-COMP-002: " + name + "兼容窗口倒置", 400);
    }
    private static void inWindow(String actual, String min, String max, String name) {
        version(actual, name);
        require(compare(actual, min) >= 0 && compare(actual, max) <= 0,
            "UPG-COMP-003: " + name + "不在兼容窗口", 409);
    }
    private static int compare(String left, String right) {
        String[] a = left.split("[-+]", 2)[0].split("\\.");
        String[] b = right.split("[-+]", 2)[0].split("\\.");
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            int x = index < a.length ? Integer.parseInt(a[index]) : 0;
            int y = index < b.length ? Integer.parseInt(b[index]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
    private static void version(String value, String name) { require(value != null && VERSION.matcher(value).matches(), "UPG-COMP-004: " + name + "格式无效", 400); }
    private static TaskState requireTask(TaskState from, Set<TaskState> allowed, TaskState target) {
        require(allowed.contains(from), "UPG-STATE-003: 非法任务状态迁移", 409); return target;
    }
    private static boolean schema(ArtifactType type) { return type == ArtifactType.MYSQL_SCHEMA || type == ArtifactType.SQLITE_SCHEMA; }
    private static void requireText(String value, int max, String name) { require(value != null && !value.isBlank() && value.length() <= max, "UPG-INPUT-009: " + name + "无效", 400); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void require(boolean condition, String message, int code) { if (!condition) throw new ServiceException(message, code); }
}
