package com.jingshanghui.pos.catalog.application.port;

import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskItemView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TemplateView;

import java.time.Instant;
import java.util.List;

/** ShelfLabel Owner 的持久化端口；只暴露具名写能力，不暴露跨 Owner Mapper。 */
public interface ShelfLabelRepository {

    void insertTemplate(TemplateDraft draft);

    StoredTemplateCommand findTemplateCommand(String tenantId, String idempotencyKey);

    TemplateView findTemplate(String tenantId, Long templateId);

    TemplateView findPublishedTemplate(String tenantId, Long storeId);

    List<TemplateView> listTemplates(String tenantId, List<Long> storeIds, String state, int limit);

    int publishTemplate(String tenantId, Long templateId, int expectedVersion, String contentSha256, Instant publishedAt);

    int retireTemplate(String tenantId, Long templateId, int expectedVersion);

    StoredTask findTaskBySource(String tenantId, String sourceEventKey);

    void insertTask(GeneratedTask task);

    void insertItem(GeneratedItem item);

    LatestOpenItem findLatestOpenItem(String tenantId, Long storeId, Long skuId, Long unitId);

    List<Long> findOpenTaskIds(String tenantId, Long storeId, Long skuId, Long unitId);

    int supersedeOpenItems(String tenantId, Long storeId, Long skuId, Long unitId, Long replacingItemId,
                           Instant incomingEffectiveAt, int incomingScopePriority, int incomingVersion,
                           Long incomingPriceBookId, Instant occurredAt);

    List<TaskView> listTasks(String tenantId, List<Long> storeIds, String state, int limit);

    TaskView findTask(String tenantId, Long taskId);

    List<TaskItemView> listTaskItems(String tenantId, Long taskId);

    TaskItemView findTaskItem(String tenantId, Long itemId);

    int transitionItem(String tenantId, Long itemId, String expectedState, int expectedVersion,
                       String targetState, String exceptionReason, Instant updatedAt);

    int markTaskDispatchBlocked(String tenantId, Long taskId, int expectedVersion, Instant updatedAt);

    void refreshTaskProjection(String tenantId, Long taskId, Instant updatedAt);

    StoredCommand findCommand(String tenantId, String idempotencyKey);

    void appendEvent(LabelEvent event);

    void appendException(LabelException exception);

    /** @param templateId 主键 @param tenantId 可信租户 @param templateCode 编码 @param templateName 名称
     * @param versionNo 业务版本 @param scopeType TENANT/STORE @param storeId 可选门店
     * @param bodyTemplate 纯文本模板 @param createdAt 创建时间 */
    record TemplateDraft(Long templateId, String tenantId, String templateCode, String templateName,
                         int versionNo, String scopeType, Long storeId, String bodyTemplate,
                         String idempotencyKey, String requestSha256, Instant createdAt) {
    }

    /** @param templateId 已创建模板 @param requestSha256 创建命令摘要 */
    record StoredTemplateCommand(Long templateId, String requestSha256) {
    }

    /** @param taskId 任务主键 @param tenantId 可信租户 @param sourceEventKey 稳定来源键
     * @param sourceEventType 来源类型 @param sourceEventSha256 来源摘要 @param sourcePriceBookId 价格簿
     * @param sourcePriceVersion 价格版本 @param storeId 门店 @param storeName 门店名称
     * @param effectiveAt 最早生效时间 @param state 初始状态 @param createdAt 创建时间 */
    record GeneratedTask(Long taskId, String tenantId, String sourceEventKey, String sourceEventType,
                         String sourceEventSha256, Long sourcePriceBookId, int sourcePriceVersion,
                         Long storeId, String storeName, Instant effectiveAt, String state, Instant createdAt) {
    }

    /** 冻结到任务项的商品、单位和价格快照。 */
    record GeneratedItem(Long itemId, String tenantId, Long taskId, Long sourcePriceBookId,
                         Long sourcePriceItemId, int sourcePriceVersion, int scopePriority,
                         Long storeId, String storeName, Long skuId, String skuCode, String productName,
                         Long unitId, String unitName, String barcode, Long oldPriceMinor, Long newPriceMinor,
                         String currency, Instant effectiveAt, String state, String exceptionReason,
                         String snapshotSha256, Instant createdAt) {
    }

    /** @param taskId 已存在任务 @param sourceEventSha256 已冻结来源摘要 */
    record StoredTask(Long taskId, String sourceEventSha256) {
    }

    /** 未完成任务项的排序元组。 */
    record LatestOpenItem(Long itemId, Instant effectiveAt, Integer scopePriority,
                          Integer sourcePriceVersion, Long sourcePriceBookId) {
    }

    /** @param eventType 命令类型 @param commandSha256 命令摘要 @param taskId 任务 @param itemId 任务项 */
    record StoredCommand(String eventType, String commandSha256, Long taskId, Long itemId) {
    }

    /** 价签工作流只追加事件。 */
    record LabelEvent(Long eventId, String tenantId, Long taskId, Long itemId, String eventType,
                      String idempotencyKey, String commandSha256, String payloadSha256, String payloadJson,
                      Long actorUserId, String correlationId, Instant occurredAt) {
    }

    /** 价签异常及受控处置事实。 */
    record LabelException(Long exceptionId, String tenantId, Long taskId, Long itemId, String exceptionCode,
                          String reason, String resolutionType, Long actorUserId, String correlationId,
                          Instant occurredAt) {
    }
}
