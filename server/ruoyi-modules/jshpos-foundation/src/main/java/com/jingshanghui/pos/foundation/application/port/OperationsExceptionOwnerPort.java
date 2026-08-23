package com.jingshanghui.pos.foundation.application.port;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 各领域 Owner 向 Operations 异常中心公开的窄端口。
 *
 * <p>实现只能读取或命令本 Owner 的事实；tenantId 由实现内可信上下文取得，调用方不得
 * 传入。未支持的修复必须返回 UNAVAILABLE，禁止伪造成功。</p>
 */
public interface OperationsExceptionOwnerPort {
    /** Owner 稳定代码。 */
    String ownerCode();

    /** 扫描可信门店与业务日范围内的异常事实，最多返回 limit 项。 */
    List<OwnerObservation> scan(Long storeId, LocalDate businessDate, int limit);

    /** 使用原稳定命令观察或执行本 Owner 的具名修复；不得生成替代业务命令。 */
    OwnerRepairResult repair(OwnerRepairCommand command);

    /**
     * Owner 产生的去敏、可验真异常观察。
     *
     * @param sourceType Owner 内稳定异常类型
     * @param sourceFactId Owner 内稳定事实标识
     * @param sourceEventId 本次观察的稳定事件标识
     * @param sourceSequence Owner 提供的单调或稳定序号
     * @param sourceSha256 来源事实规范内容摘要
     * @param dedupKey 同一业务异常的稳定去重键
     * @param severity 严重级别 P0 至 P3
     * @param correlationId 跨 Owner 关联标识
     * @param observedAt Owner 观察到异常的 UTC 时间
     * @param maskedSummary 不含 Secret 与 PII 的摘要
     * @param allowedRepairAction Owner 允许的具名修复动作
     */
    record OwnerObservation(String sourceType, String sourceFactId, String sourceEventId,
                            long sourceSequence, String sourceSha256, String dedupKey,
                            String severity, String correlationId, LocalDateTime observedAt,
                            String maskedSummary, String allowedRepairAction) { }

    /**
     * Operations 发往 Owner 的稳定修复命令；tenant/store 均来自可信案件。
     *
     * @param commandId 修复命令稳定标识
     * @param storeId 可信案件绑定门店
     * @param sourceType 来源异常类型
     * @param sourceFactId 来源事实标识
     * @param sourceEventId 来源事件标识
     * @param sourceSequence 来源序号
     * @param sourceSha256 来源事实摘要
     * @param actionCode Owner 具名修复动作
     * @param requestSha256 修复请求规范摘要
     * @param idempotencyKey 稳定幂等键
     * @param correlationId 跨 Owner 关联标识
     */
    record OwnerRepairCommand(String commandId, Long storeId, String sourceType,
                              String sourceFactId, String sourceEventId, long sourceSequence,
                              String sourceSha256, String actionCode, String requestSha256,
                              String idempotencyKey, String correlationId) { }

    /**
     * Owner 结果仅允许具名状态和摘要，不回传 Secret/PII 或原始报文。
     *
     * @param status SUCCEEDED/WAITING_OWNER/UNAVAILABLE/FAILED
     * @param resultReference Owner 结果稳定引用
     * @param resultSha256 Owner 结果规范摘要
     * @param maskedMessage 去敏结果说明
     */
    record OwnerRepairResult(String status, String resultReference, String resultSha256,
                             String maskedMessage) { }
}
