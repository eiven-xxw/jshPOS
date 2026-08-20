package com.jingshanghui.pos.release.application.port;

import com.jingshanghui.pos.release.domain.ReleaseModels.*;

import java.time.Instant;
import java.security.PublicKey;
import java.util.Optional;
import java.util.Set;

/** Release Owner 的持久化、发布物校验、可信终端和营业保护端口。 */
public final class ReleasePorts {
    private ReleasePorts() { }

    /** 复杂状态和只追加事实持久化端口；实现必须使用具名条件SQL。 */
    public interface Repository {
        Optional<Release> findRelease(String tenantId, String releaseId);
        Optional<Rollout> findRollout(String tenantId, String rolloutId);
        Optional<TerminalTask> findTask(String tenantId, String taskId);
        Optional<CommandResult> findCommand(String tenantId, String commandType, String idempotencyKey);
        void insertRelease(Release release, String requestSha256, long actorId, String correlationId);
        void transitionRelease(String tenantId, String releaseId, ReleaseState from, ReleaseState to,
                               long expectedVersion, long actorId, String correlationId, String evidenceSha256);
        void insertRollout(Rollout rollout, String requestSha256, long actorId, String correlationId);
        void transitionRollout(String tenantId, String rolloutId, RolloutState from, RolloutState to,
                               long expectedVersion, long actorId, String correlationId, String evidenceSha256);
        void insertTask(TerminalTask task, String requestSha256, long actorId, String correlationId);
        void transitionTask(String tenantId, String taskId, TaskState from, TaskState to,
                            long expectedVersion, String evidenceSha256, long actorId, String correlationId);
        TaskSummary summarizeTasks(String tenantId, String rolloutId);
        void appendCommand(String tenantId, String commandType, String idempotencyKey, String requestSha256,
                           String aggregateId, String resultCode, long actorId, Instant occurredAt);
    }

    /** 从对象存储和受控密钥注册表校验摘要、签名和密钥版本；缺失实现必须失败关闭。 */
    public interface ArtifactVerifier { ArtifactObservation verify(Release release); }
    /** 私有对象存储只读端口；实现必须校验租户命名空间和大小上限。 */
    public interface ArtifactBinarySource { byte[] read(String objectKey); }
    /** 受控公钥注册表；仅返回公钥，私钥不得进入运行时。 */
    public interface PublicKeyRegistry { PublicKey resolve(String keyVersion); }
    /** 从终端注册表加载可信租户、门店、版本和能力；不得采信请求体范围。 */
    public interface TrustedTerminalRegistry { TrustedTerminal require(String tenantId, String deviceId); }
    /** 从同步、支付、退款、班次和终端遥测 Owner 合并安全快照；缺任一来源必须失败关闭。 */
    public interface SafetyProbe { SafetySnapshot inspect(TrustedTerminal terminal, Release release); }
    /** 由租户管理员数据范围适配器提供的受权门店集合。 */
    public interface AuthorizedStores { void require(Set<Long> storeIds); }
}
