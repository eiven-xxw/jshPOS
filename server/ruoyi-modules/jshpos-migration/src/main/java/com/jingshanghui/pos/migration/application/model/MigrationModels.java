package com.jingshanghui.pos.migration.application.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** T2-DMT-001 应用命令与脱敏读模型；tenant_id 永远不由客户端提供。 */
public final class MigrationModels {
    private MigrationModels() {
    }

    /**
     * 建立隔离迁移批次的命令。
     * @param dataTypes 本批次必需资料类型集合
     * @param idempotencyKey 创建批次稳定幂等键
     * @param correlationId 全链路关联标识
     */
    public record CreateBatch(Set<String> dataTypes, String idempotencyKey, String correlationId) {
        public CreateBatch { dataTypes = dataTypes == null ? Set.of() : Set.copyOf(dataTypes); }
    }
    /**
     * 上传并预检单类资料的请求内命令。
     * @param batchId 迁移批次 ULID
     * @param dataType 资料类型
     * @param mappingVersion 冻结映射版本
     * @param originalFilename 未用于路径访问的原逻辑文件名
     * @param charset CSV 字符集或 XLSX 标识
     * @param sourceSystem 来源系统说明
     * @param custodyReference 受控原文件保管引用
     * @param declaredSha256 调用方声明的原文件 SHA-256
     * @param content 仅在请求内存在的原文件字节副本
     * @param correlationId 全链路关联标识
     */
    public record UploadFile(String batchId, String dataType, String mappingVersion, String originalFilename,
                             String charset, String sourceSystem, String custodyReference,
                             String declaredSha256, byte[] content, String correlationId) {
        public UploadFile { content = content == null ? new byte[0] : content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
    /**
     * 审批、恢复、对账、激活和清理共用的具名批次命令。
     * @param batchId 迁移批次 ULID
     * @param idempotencyKey 当前动作稳定幂等键
     * @param reason 受审计操作原因
     * @param correlationId 全链路关联标识
     */
    public record BatchCommand(String batchId, String idempotencyKey, String reason, String correlationId) { }

    /**
     * 不含 staging 明文的迁移批次概要。
     * @param batchId 迁移批次 ULID
     * @param state 当前批次状态
     * @param requestedTypes 必需资料类型集合
     * @param fileCount 已登记文件数
     * @param validRowCount 通过预检的有效行数
     * @param errorCount 完整预检错误总数
     * @param approvalCount 不同审批人数量
     * @param appliedRowCount 已保存 Owner 检查点行数
     * @param version 乐观并发版本
     * @param requestSha256 创建请求规范摘要
     * @param correlationId 创建批次关联标识
     * @param createdAt UTC 创建时间
     */
    public record BatchView(String batchId, String state, Set<String> requestedTypes, int fileCount,
                            int validRowCount, int errorCount, int approvalCount, int appliedRowCount,
                            int version, String requestSha256, String correlationId, LocalDateTime createdAt) {
        public BatchView { requestedTypes = requestedTypes == null ? Set.of() : Set.copyOf(requestedTypes); }
    }
    /**
     * 只展示元数据的迁移文件视图。
     * @param fileId 文件登记 ULID
     * @param batchId 所属批次 ULID
     * @param dataType 资料类型
     * @param mappingVersion 冻结映射版本
     * @param sourceSha256 原文件 SHA-256
     * @param safeFilename 安全逻辑文件名
     * @param charset CSV 字符集或 XLSX 标识
     * @param rowCount 有效行数
     * @param errorCount 预检错误数
     * @param state 文件预检状态
     * @param sourceSystem 来源系统说明
     * @param custodyReference 受控保管引用
     */
    public record FileView(String fileId, String batchId, String dataType, String mappingVersion,
                           String sourceSha256, String safeFilename, String charset, int rowCount,
                           int errorCount, String state, String sourceSystem, String custodyReference) { }
    /**
     * 不回显原值的预检错误。
     * @param errorId 错误事实 ULID
     * @param dataType 资料类型
     * @param rowNumber 文件行号
     * @param fieldName 字段名
     * @param errorCode 稳定错误码
     * @param maskedMessage 脱敏错误说明
     */
    public record PreflightErrorView(String errorId, String dataType, int rowNumber, String fieldName,
                                     String errorCode, String maskedMessage) { }
    /**
     * 预检错误稳定分页。
     * @param page 从 1 开始的页码
     * @param pageSize 每页条数
     * @param total 完整错误总数
     * @param records 当前页脱敏错误
     */
    public record PreflightErrorPage(int page, int pageSize, int total,
                                     List<PreflightErrorView> records) {
        public PreflightErrorPage { records = records == null ? List.of() : List.copyOf(records); }
    }
    /**
     * 按 Owner 与资料类型聚合的检查点视图。
     * @param ownerType 目标数据 Owner
     * @param dataType 资料类型
     * @param appliedCount 已应用行数
     * @param failedCount 失败行数
     * @param resultSha256 完整有序结果摘要
     * @param state 聚合检查点状态
     */
    public record CheckpointView(String ownerType, String dataType, int appliedCount, int failedCount,
                                 String resultSha256, String state) { }
    /**
     * 迁移向导读取的批次详情。
     * @param batch 批次概要
     * @param files 文件元数据
     * @param errors 首批脱敏错误，完整结果通过分页端点读取
     * @param checkpoints Owner 检查点聚合
     */
    public record BatchDetail(BatchView batch, List<FileView> files, List<PreflightErrorView> errors,
                              List<CheckpointView> checkpoints) {
        public BatchDetail {
            files = files == null ? List.of() : List.copyOf(files);
            errors = errors == null ? List.of() : List.copyOf(errors);
            checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
        }
    }
    /**
     * 上传与完整预检结果。
     * @param batch 最新批次概要
     * @param file 文件元数据
     * @param acceptedRows 通过预检的行数
     * @param errorCount 阻断错误数
     */
    public record UploadResult(BatchView batch, FileView file, int acceptedRows, int errorCount) { }
    /**
     * Owner Saga 对账结果。
     * @param batchId 迁移批次 ULID
     * @param expectedRows staging 期望行数
     * @param appliedRows 已保存 Owner 检查点行数
     * @param differenceCount 差异数量
     * @param resultSha256 对账输入与结果摘要
     * @param go 是否满足零差异激活条件
     */
    public record ReconciliationResult(String batchId, int expectedRows, int appliedRows,
                                       int differenceCount, String resultSha256, boolean go) { }
}
