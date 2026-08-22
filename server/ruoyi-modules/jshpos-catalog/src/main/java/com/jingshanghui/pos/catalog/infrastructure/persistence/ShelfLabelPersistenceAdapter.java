package com.jingshanghui.pos.catalog.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskItemView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TemplateView;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository;
import com.jingshanghui.pos.catalog.infrastructure.persistence.entity.ShelfLabelTaskEntity;
import com.jingshanghui.pos.catalog.infrastructure.persistence.entity.ShelfLabelTaskItemEntity;
import com.jingshanghui.pos.catalog.infrastructure.persistence.entity.ShelfLabelTemplateEntity;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.ShelfLabelFactMapper;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.ShelfLabelTaskItemMapper;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.ShelfLabelTaskMapper;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.ShelfLabelTemplateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** ShelfLabel Owner 的 MyBatis-Plus/XML 混合持久化适配器。 */
@Repository
@RequiredArgsConstructor
public class ShelfLabelPersistenceAdapter implements ShelfLabelRepository {

    private final ShelfLabelTemplateMapper templateMapper;
    private final ShelfLabelTaskMapper taskMapper;
    private final ShelfLabelTaskItemMapper itemMapper;
    private final ShelfLabelFactMapper factMapper;

    @Override
    public void insertTemplate(TemplateDraft draft) {
        ShelfLabelTemplateEntity entity = new ShelfLabelTemplateEntity();
        entity.setTemplateId(draft.templateId());
        entity.setTenantId(draft.tenantId());
        entity.setTemplateCode(draft.templateCode());
        entity.setTemplateName(draft.templateName());
        entity.setVersionNo(draft.versionNo());
        entity.setScopeType(draft.scopeType());
        entity.setStoreId(draft.storeId());
        entity.setBodyTemplate(draft.bodyTemplate());
        entity.setCreateIdempotencyKey(draft.idempotencyKey());
        entity.setCreateRequestSha256(draft.requestSha256());
        entity.setState("DRAFT");
        entity.setCreatedAt(local(draft.createdAt()));
        entity.setVersion(0);
        templateMapper.insert(entity);
    }

    @Override
    public StoredTemplateCommand findTemplateCommand(String tenantId, String idempotencyKey) {
        return templateMapper.findTemplateCommand(tenantId, idempotencyKey);
    }

    @Override
    public TemplateView findTemplate(String tenantId, Long templateId) {
        ShelfLabelTemplateEntity entity = templateMapper.selectOne(
            new LambdaQueryWrapper<ShelfLabelTemplateEntity>()
                .eq(ShelfLabelTemplateEntity::getTenantId, tenantId)
                .eq(ShelfLabelTemplateEntity::getTemplateId, templateId));
        return entity == null ? null : templateView(entity);
    }

    @Override
    public TemplateView findPublishedTemplate(String tenantId, Long storeId) {
        return templateMapper.findPublishedTemplate(tenantId, storeId);
    }

    @Override
    public List<TemplateView> listTemplates(String tenantId, List<Long> storeIds, String state, int limit) {
        return templateMapper.listTemplates(tenantId, storeIds, state, limit);
    }

    @Override
    public int publishTemplate(String tenantId, Long templateId, int expectedVersion,
                               String contentSha256, Instant publishedAt) {
        return templateMapper.update(null, new LambdaUpdateWrapper<ShelfLabelTemplateEntity>()
            .eq(ShelfLabelTemplateEntity::getTenantId, tenantId)
            .eq(ShelfLabelTemplateEntity::getTemplateId, templateId)
            .eq(ShelfLabelTemplateEntity::getState, "DRAFT")
            .eq(ShelfLabelTemplateEntity::getVersion, expectedVersion)
            .set(ShelfLabelTemplateEntity::getState, "PUBLISHED")
            .set(ShelfLabelTemplateEntity::getContentSha256, contentSha256)
            .set(ShelfLabelTemplateEntity::getPublishedAt, local(publishedAt))
            .set(ShelfLabelTemplateEntity::getVersion, expectedVersion + 1));
    }

    @Override
    public int retireTemplate(String tenantId, Long templateId, int expectedVersion) {
        return templateMapper.update(null, new LambdaUpdateWrapper<ShelfLabelTemplateEntity>()
            .eq(ShelfLabelTemplateEntity::getTenantId, tenantId)
            .eq(ShelfLabelTemplateEntity::getTemplateId, templateId)
            .eq(ShelfLabelTemplateEntity::getState, "PUBLISHED")
            .eq(ShelfLabelTemplateEntity::getVersion, expectedVersion)
            .set(ShelfLabelTemplateEntity::getState, "RETIRED")
            .set(ShelfLabelTemplateEntity::getVersion, expectedVersion + 1));
    }

    @Override
    public StoredTask findTaskBySource(String tenantId, String sourceEventKey) {
        return taskMapper.findBySource(tenantId, sourceEventKey);
    }

    @Override
    public void insertTask(GeneratedTask task) {
        ShelfLabelTaskEntity entity = new ShelfLabelTaskEntity();
        entity.setTaskId(task.taskId());
        entity.setTenantId(task.tenantId());
        entity.setSourceEventKey(task.sourceEventKey());
        entity.setSourceEventType(task.sourceEventType());
        entity.setSourceEventSha256(task.sourceEventSha256());
        entity.setSourcePriceBookId(task.sourcePriceBookId());
        entity.setSourcePriceVersion(task.sourcePriceVersion());
        entity.setStoreId(task.storeId());
        entity.setStoreName(task.storeName());
        entity.setEffectiveAt(local(task.effectiveAt()));
        entity.setState(task.state());
        entity.setCreatedAt(local(task.createdAt()));
        entity.setUpdatedAt(local(task.createdAt()));
        entity.setVersion(0);
        taskMapper.insert(entity);
    }

    @Override
    public void insertItem(GeneratedItem item) {
        ShelfLabelTaskItemEntity entity = new ShelfLabelTaskItemEntity();
        entity.setItemId(item.itemId());
        entity.setTenantId(item.tenantId());
        entity.setTaskId(item.taskId());
        entity.setSourcePriceBookId(item.sourcePriceBookId());
        entity.setSourcePriceItemId(item.sourcePriceItemId());
        entity.setSourcePriceVersion(item.sourcePriceVersion());
        entity.setScopePriority(item.scopePriority());
        entity.setStoreId(item.storeId());
        entity.setStoreName(item.storeName());
        entity.setSkuId(item.skuId());
        entity.setSkuCode(item.skuCode());
        entity.setProductName(item.productName());
        entity.setUnitId(item.unitId());
        entity.setUnitName(item.unitName());
        entity.setBarcode(item.barcode());
        entity.setOldPriceMinor(item.oldPriceMinor());
        entity.setNewPriceMinor(item.newPriceMinor());
        entity.setCurrency(item.currency());
        entity.setEffectiveAt(local(item.effectiveAt()));
        entity.setState(item.state());
        entity.setExceptionReason(item.exceptionReason());
        entity.setSnapshotSha256(item.snapshotSha256());
        entity.setCreatedAt(local(item.createdAt()));
        entity.setUpdatedAt(local(item.createdAt()));
        entity.setVersion(0);
        itemMapper.insert(entity);
    }

    @Override
    public LatestOpenItem findLatestOpenItem(String tenantId, Long storeId, Long skuId, Long unitId) {
        return itemMapper.findLatestOpenItem(tenantId, storeId, skuId, unitId);
    }

    @Override
    public List<Long> findOpenTaskIds(String tenantId, Long storeId, Long skuId, Long unitId) {
        return itemMapper.findOpenTaskIds(tenantId, storeId, skuId, unitId);
    }

    @Override
    public int supersedeOpenItems(String tenantId, Long storeId, Long skuId, Long unitId, Long replacingItemId,
                                  Instant incomingEffectiveAt, int incomingScopePriority, int incomingVersion,
                                  Long incomingPriceBookId, Instant occurredAt) {
        return itemMapper.supersedeOpenItems(tenantId, storeId, skuId, unitId, replacingItemId,
            local(incomingEffectiveAt), incomingScopePriority, incomingVersion, incomingPriceBookId, local(occurredAt));
    }

    @Override
    public List<TaskView> listTasks(String tenantId, List<Long> storeIds, String state, int limit) {
        return taskMapper.listTasks(tenantId, storeIds, state, limit);
    }

    @Override
    public TaskView findTask(String tenantId, Long taskId) {
        return taskMapper.findTaskView(tenantId, taskId);
    }

    @Override
    public List<TaskItemView> listTaskItems(String tenantId, Long taskId) {
        return itemMapper.listTaskItems(tenantId, taskId);
    }

    @Override
    public TaskItemView findTaskItem(String tenantId, Long itemId) {
        return itemMapper.findTaskItem(tenantId, itemId);
    }

    @Override
    public int transitionItem(String tenantId, Long itemId, String expectedState, int expectedVersion,
                              String targetState, String exceptionReason, Instant updatedAt) {
        return itemMapper.transition(tenantId, itemId, expectedState, expectedVersion, targetState,
            exceptionReason, local(updatedAt));
    }

    @Override
    public int markTaskDispatchBlocked(String tenantId, Long taskId, int expectedVersion, Instant updatedAt) {
        return taskMapper.markDispatchBlocked(tenantId, taskId, expectedVersion, local(updatedAt));
    }

    @Override
    public void refreshTaskProjection(String tenantId, Long taskId, Instant updatedAt) {
        taskMapper.refreshProjection(tenantId, taskId, local(updatedAt));
    }

    @Override
    public StoredCommand findCommand(String tenantId, String idempotencyKey) {
        return factMapper.findCommand(tenantId, idempotencyKey);
    }

    @Override
    public void appendEvent(LabelEvent event) {
        factMapper.insertEvent(event);
    }

    @Override
    public void appendException(LabelException exception) {
        factMapper.insertException(exception);
    }

    private TemplateView templateView(ShelfLabelTemplateEntity entity) {
        return new TemplateView(entity.getTemplateId(), entity.getTemplateCode(), entity.getTemplateName(),
            entity.getVersionNo(), entity.getScopeType(), entity.getStoreId(), entity.getBodyTemplate(), entity.getState(),
            entity.getContentSha256(), instant(entity.getPublishedAt()), entity.getVersion());
    }

    private static LocalDateTime local(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
