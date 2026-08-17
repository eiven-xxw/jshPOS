package com.jingshanghui.pos.resilience.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** 备份、恢复及演练的不可变领域模型。 */
public final class BackupModels {
    private BackupModels() {
    }

    /** 备份必须覆盖的数据类别。 */
    public enum DataClass { MYSQL, BUSINESS_OBJECT, CONFIG, TEMPLATE, MIGRATION, EVIDENCE }

    /**
     * 备份源提供的明文对象；内容仅在进程内短暂存在。
     * @param dataClass 数据类别
     * @param logicalName 不含租户授权语义的逻辑路径
     * @param mediaType 媒体类型
     * @param tenantIds 对象实际包含的虚构或真实租户范围
     * @param content 明文字节；构造和读取均防御性复制
     */
    public record SourceObject(DataClass dataClass, String logicalName, String mediaType,
                               Set<String> tenantIds, byte[] content) {
        public SourceObject {
            tenantIds = tenantIds == null ? Set.of() : Set.copyOf(tenantIds);
            content = content == null ? new byte[0] : content.clone();
        }

        @Override public byte[] content() { return content.clone(); }
    }

    /**
     * 创建恢复集合命令；tenantId 不从客户端单值注入，而由受权备份范围显式冻结。
     * @param backupId 备份 ULID 与幂等键
     * @param environment 环境标识
     * @param tenantIds 经服务端授权的租户集合
     * @param pointInTime 恢复点 UTC
     * @param latestIncludedFactAt 最后纳入的权威事实 UTC
     * @param schemaVersion Schema 版本
     * @param applicationVersion 应用版本
     * @param keyVersion 外部密钥版本引用
     * @param immutableUntil 不可变保留截止 UTC
     * @param requestedBy 可信操作者
     * @param correlationId 关联 ULID
     */
    public record CreateBackup(String backupId, String environment, Set<String> tenantIds,
                               Instant pointInTime, Instant latestIncludedFactAt, String schemaVersion,
                               String applicationVersion, String keyVersion, Instant immutableUntil,
                               long requestedBy, String correlationId) {
        public CreateBackup { tenantIds = tenantIds == null ? Set.of() : Set.copyOf(tenantIds); }
    }

    /**
     * 恢复演练命令。
     * @param drillId 演练 ULID 与幂等键
     * @param backupId 目标备份 ULID
     * @param expectedTenantIds 期望租户范围，必须与清单完全一致
     * @param expectedSchemaVersion 允许恢复的 Schema 版本
     * @param requestedBy 可信操作者
     * @param correlationId 关联 ULID
     */
    public record RestoreBackup(String drillId, String backupId, Set<String> expectedTenantIds,
                                String expectedSchemaVersion, long requestedBy, String correlationId) {
        public RestoreBackup {
            expectedTenantIds = expectedTenantIds == null ? Set.of() : Set.copyOf(expectedTenantIds);
        }
    }

    /**
     * 已加密对象描述符。
     * @param objectId 对象 ULID
     * @param dataClass 数据类别
     * @param logicalName 逻辑名称
     * @param mediaType 媒体类型
     * @param tenantScopeSha256 租户集合摘要
     * @param plaintextSizeBytes 明文字节数
     * @param plaintextSha256 明文 SHA-256
     * @param ciphertextSizeBytes 密文字节数
     * @param ciphertextSha256 密文 SHA-256
     * @param keyVersion 密钥版本引用
     * @param nonceBase64 AES-GCM 随机 nonce
     * @param objectKey 只追加对象键
     */
    public record ObjectDescriptor(String objectId, DataClass dataClass, String logicalName, String mediaType,
                                   String tenantScopeSha256, long plaintextSizeBytes, String plaintextSha256,
                                   long ciphertextSizeBytes, String ciphertextSha256, String keyVersion,
                                   String nonceBase64, String objectKey) {
    }

    /**
     * 备份清单及状态。
     * @param backupId 备份 ULID
     * @param environment 环境
     * @param tenantIds 冻结租户集合
     * @param tenantScopeSha256 租户集合摘要
     * @param pointInTime 恢复点
     * @param latestIncludedFactAt 最后权威事实
     * @param schemaVersion Schema 版本
     * @param applicationVersion 应用版本
     * @param keyVersion 密钥版本引用
     * @param immutableUntil 不可变保留截止
     * @param requestSha256 幂等请求摘要
     * @param manifestSha256 清单摘要
     * @param manifestJson 规范清单 JSON
     * @param state 状态
     * @param objects 对象描述符
     */
    public record BackupSet(String backupId, String environment, Set<String> tenantIds,
                            String tenantScopeSha256, Instant pointInTime, Instant latestIncludedFactAt,
                            String schemaVersion, String applicationVersion, String keyVersion,
                            Instant immutableUntil, String requestSha256, String manifestSha256,
                            String manifestJson, String state, List<ObjectDescriptor> objects) {
        public BackupSet {
            tenantIds = tenantIds == null ? Set.of() : Set.copyOf(tenantIds);
            objects = objects == null ? List.of() : List.copyOf(objects);
        }
    }

    /**
     * 单项恢复校验。
     * @param code 稳定校验码
     * @param result PASS 或 FAIL_CLOSED
     * @param evidenceSha256 去敏证据摘要
     */
    public record RestoreCheck(String code, String result, String evidenceSha256) {
    }

    /**
     * 恢复演练证据。
     * @param drillId 演练 ULID
     * @param backupId 备份 ULID
     * @param requestSha256 幂等请求摘要
     * @param startedAt 开始 UTC
     * @param endedAt 结束 UTC
     * @param rpoSeconds 恢复点目标秒数
     * @param rtoSeconds 自动计时秒数
     * @param result PASS 或 FAIL_CLOSED
     * @param evidenceSha256 证据摘要
     * @param checks 只追加校验结果
     */
    public record RestoreEvidence(String drillId, String backupId, String requestSha256, Instant startedAt,
                                  Instant endedAt, long rpoSeconds, long rtoSeconds, String result,
                                  String evidenceSha256, List<RestoreCheck> checks) {
        public RestoreEvidence { checks = checks == null ? List.of() : List.copyOf(checks); }
    }

    /**
     * 隔离恢复目标给出的权威核对结果。
     * @param flywayValidated Flyway 校验是否通过
     * @param projectionRebuilt 可丢弃投影是否重建
     * @param tenantDifferences 租户守恒未解释差异数
     * @param businessDayDifferences 业务日守恒未解释差异数
     * @param cursorDifferences Inbox/Outbox/游标差异数
     * @param auditDifferences 不可变审计差异数
     */
    public record Reconciliation(boolean flywayValidated, boolean projectionRebuilt,
                                 long tenantDifferences, long businessDayDifferences,
                                 long cursorDifferences, long auditDifferences) {
    }
}
