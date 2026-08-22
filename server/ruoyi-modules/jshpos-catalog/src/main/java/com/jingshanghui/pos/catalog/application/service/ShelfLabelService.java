package com.jingshanghui.pos.catalog.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.CreateTemplateCommand;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.PreviewView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.PriceBookEvent;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskDetailView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskItemView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TemplateView;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelPriceEventPort;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelPrintPort;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.GeneratedItem;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.GeneratedTask;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.LabelEvent;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.LabelException;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.LatestOpenItem;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.StoredCommand;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.StoredTask;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.StoredTemplateCommand;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.TemplateDraft;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelSourcePort;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelSourcePort.PriceSource;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelStorePort;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelStorePort.StoreSnapshot;
import com.jingshanghui.pos.catalog.domain.CatalogRules;
import com.jingshanghui.pos.catalog.domain.ShelfLabelRules;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 货架价签应用服务。
 *
 * <p>该服务只编排 ShelfLabel Owner 自有事实，通过只读端口消费价格、商品和门店；
 * 任何打印请求在 T2-PRN-001 解阻前都必须记录异常并失败关闭。</p>
 */
@Service
@RequiredArgsConstructor
public class ShelfLabelService implements ShelfLabelPriceEventPort {

    private static final int MAX_TASK_LIST = 200;

    private final ShelfLabelRepository repository;
    private final ShelfLabelSourcePort sourcePort;
    private final ShelfLabelStorePort storePort;
    private final ShelfLabelPrintPort printPort;
    private final TrustedTenantContext tenantContext;
    private final DomainAuditService auditService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /** 创建模板草稿；模板内容先完成白名单和注入校验。 */
    @Transactional
    public TemplateView createTemplate(CreateTemplateCommand command) {
        String tenantId = tenantContext.requireTenantId();
        requireCommandIdentity(command.idempotencyKey(), command.correlationId());
        String scope = requireTemplateScope(command.scopeType(), command.storeId());
        if (command.versionNo() <= 0) throw new ServiceException("LBL-TPL-005: 模板版本必须为正整数", 400);
        if (command.storeId() != null) storePort.requireAccessibleStore(command.storeId());
        String body = ShelfLabelRules.requireSafeTemplate(command.bodyTemplate());
        String code = CatalogRules.requireCode(command.templateCode(), "LBL-TPL-006");
        String name = CatalogRules.requireName(command.templateName());
        String requestHash = ShelfLabelRules.sha256(String.join("|", code, name,
            String.valueOf(command.versionNo()), scope, Objects.toString(command.storeId(), ""), body));
        StoredTemplateCommand existing = repository.findTemplateCommand(tenantId, command.idempotencyKey());
        StoredCommand storedCommand = repository.findCommand(tenantId, command.idempotencyKey());
        if (existing != null) {
            if (!requestHash.equals(existing.requestSha256())
                || storedCommand != null && !requestHash.equals(storedCommand.commandSha256())) throw idempotencyConflict();
            return requireTemplate(tenantId, existing.templateId());
        }
        if (storedCommand != null) throw idempotencyConflict();
        Long templateId = IdWorker.getId();
        repository.insertTemplate(new TemplateDraft(templateId, tenantId,
            code, name, command.versionNo(), scope, command.storeId(), body,
            command.idempotencyKey(), requestHash, clock.instant()));
        TemplateView created = requireTemplate(tenantId, templateId);
        appendEvent(tenantId, null, null, "SHELF_LABEL_TEMPLATE_CREATED", command.idempotencyKey(), requestHash,
            json(Map.of("templateId", templateId, "templateCode", code, "versionNo", command.versionNo())),
            command.correlationId());
        auditService.append("SHELF_LABEL_TEMPLATE_CREATED", "SHELF_LABEL_TEMPLATE", templateId,
            null, created, Map.of("scope", scope, "versionNo", command.versionNo()));
        return created;
    }

    /** 发布不可变模板版本。 */
    @Transactional
    public TemplateView publishTemplate(Long templateId, int expectedVersion,
                                        String idempotencyKey, String correlationId) {
        String tenantId = tenantContext.requireTenantId();
        requireCommandIdentity(idempotencyKey, correlationId);
        TemplateView before = requireTemplate(tenantId, templateId);
        requireTemplateAccess(before);
        String hash = ShelfLabelRules.sha256(ShelfLabelRules.requireSafeTemplate(before.bodyTemplate()));
        String commandHash = ShelfLabelRules.sha256("TEMPLATE_PUBLISH|" + templateId + "|" + expectedVersion + "|" + hash);
        StoredCommand replay = repository.findCommand(tenantId, idempotencyKey);
        if (replay != null) {
            if (!commandHash.equals(replay.commandSha256())) throw idempotencyConflict();
            return before;
        }
        if (!"DRAFT".equals(before.state())) throw new ServiceException("LBL-TPL-007: 仅草稿模板可发布", 409);
        if (repository.publishTemplate(tenantId, templateId, expectedVersion, hash, clock.instant()) != 1) {
            throw new ServiceException("LBL-TPL-008: 模板发布并发冲突", 409);
        }
        TemplateView after = requireTemplate(tenantId, templateId);
        appendEvent(tenantId, null, null, "SHELF_LABEL_TEMPLATE_PUBLISHED", idempotencyKey, commandHash,
            json(Map.of("templateId", templateId, "contentSha256", hash)), correlationId);
        auditService.append("SHELF_LABEL_TEMPLATE_PUBLISHED", "SHELF_LABEL_TEMPLATE", templateId,
            before, after, Map.of("contentSha256", hash));
        return after;
    }

    /** 停用模板但保留历史预览身份。 */
    @Transactional
    public TemplateView retireTemplate(Long templateId, int expectedVersion,
                                       String idempotencyKey, String correlationId) {
        String tenantId = tenantContext.requireTenantId();
        requireCommandIdentity(idempotencyKey, correlationId);
        TemplateView before = requireTemplate(tenantId, templateId);
        requireTemplateAccess(before);
        String commandHash = ShelfLabelRules.sha256("TEMPLATE_RETIRE|" + templateId + "|" + expectedVersion + "|"
            + Objects.toString(before.contentSha256(), ""));
        StoredCommand replay = repository.findCommand(tenantId, idempotencyKey);
        if (replay != null) {
            if (!commandHash.equals(replay.commandSha256())) throw idempotencyConflict();
            return before;
        }
        if (!"PUBLISHED".equals(before.state()) || repository.retireTemplate(tenantId, templateId, expectedVersion) != 1) {
            throw new ServiceException("LBL-TPL-009: 仅已发布模板可按期望版本停用", 409);
        }
        TemplateView after = requireTemplate(tenantId, templateId);
        appendEvent(tenantId, null, null, "SHELF_LABEL_TEMPLATE_RETIRED", idempotencyKey, commandHash,
            json(Map.of("templateId", templateId, "contentSha256", Objects.toString(before.contentSha256(), ""))),
            correlationId);
        auditService.append("SHELF_LABEL_TEMPLATE_RETIRED", "SHELF_LABEL_TEMPLATE", templateId,
            before, after, Map.of("contentSha256", Objects.toString(before.contentSha256(), "")));
        return after;
    }

    /** 按可信门店数据范围查询租户级和门店级模板。 */
    @Transactional(readOnly = true)
    public List<TemplateView> listTemplates(String state, int limit) {
        String tenantId = tenantContext.requireTenantId();
        List<Long> storeIds = storePort.listAccessibleActiveStores().stream().map(StoreSnapshot::storeId).toList();
        String normalized = state == null || state.isBlank() ? null : state.trim().toUpperCase(Locale.ROOT);
        return repository.listTemplates(tenantId, storeIds, normalized, Math.max(1, Math.min(limit, MAX_TASK_LIST)));
    }

    /**
     * 在价格发布/停用事务内原子生成按门店任务；调用方不得用新命令重试未知结果，
     * 只能重放同一 PriceBookEvent。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> handle(PriceBookEvent event) {
        String tenantId = tenantContext.requireTenantId();
        validatePriceEvent(event);
        List<PriceSource> sources = sourcePort.listPriceSources(tenantId, event.priceBookId());
        if (sources.isEmpty()) throw new ServiceException("LBL-SRC-001: 价格版本没有可生成价签的价格项", 409);
        List<StoreSnapshot> stores = targetStores(event);
        if (stores.isEmpty()) throw new ServiceException("LBL-SCOPE-002: 没有可访问的有效目标门店", 409);
        List<Long> tasks = new ArrayList<>();
        for (StoreSnapshot store : stores) tasks.add(generateStoreTask(tenantId, event, store, sources));
        return List.copyOf(tasks);
    }

    /** 按可信门店数据范围查询任务。 */
    @Transactional(readOnly = true)
    public List<TaskView> listTasks(Long storeId, String state, int limit) {
        String tenantId = tenantContext.requireTenantId();
        List<Long> stores = storeId == null
            ? storePort.listAccessibleActiveStores().stream().map(StoreSnapshot::storeId).toList()
            : List.of(storePort.requireAccessibleStore(storeId).storeId());
        if (stores.isEmpty()) return List.of();
        String normalized = state == null || state.isBlank() ? null : state.trim().toUpperCase(Locale.ROOT);
        return repository.listTasks(tenantId, stores, normalized, Math.max(1, Math.min(limit, MAX_TASK_LIST)));
    }

    /** 查询任务和全部冻结项。 */
    @Transactional(readOnly = true)
    public TaskDetailView detail(Long taskId) {
        String tenantId = tenantContext.requireTenantId();
        TaskView task = requireTask(tenantId, taskId);
        storePort.requireAccessibleStore(task.storeId());
        return new TaskDetailView(task, repository.listTaskItems(tenantId, taskId));
    }

    /** 生成安全纯文本预览，并将待处理/异常项推进到 PREVIEW_READY。 */
    @Transactional
    public PreviewView preview(Long itemId, Long templateId, String idempotencyKey, String correlationId) {
        String tenantId = tenantContext.requireTenantId();
        requireCommandIdentity(idempotencyKey, correlationId);
        TaskItemView item = requireItem(tenantId, itemId);
        storePort.requireAccessibleStore(item.storeId());
        TemplateView template = requirePublishedTemplate(tenantId, templateId, item.storeId());
        String commandHash = ShelfLabelRules.sha256("PREVIEW|" + itemId + "|" + template.templateId() + "|" + item.version());
        StoredCommand replay = repository.findCommand(tenantId, idempotencyKey);
        if (replay != null && !commandHash.equals(replay.commandSha256())) throw idempotencyConflict();
        String rendered;
        try {
            rendered = ShelfLabelRules.render(template.bodyTemplate(), previewValues(item));
        } catch (ServiceException exception) {
            recordPreviewFailure(tenantId, item, exception.getMessage(), idempotencyKey, commandHash, correlationId);
            throw exception;
        }
        String previewHash = ShelfLabelRules.sha256(template.contentSha256() + "|" + itemId + "|" + rendered);
        if (replay != null) {
            return new PreviewView(template.templateId(), template.versionNo(), template.contentSha256(),
                item, rendered, previewHash);
        }
        if (SetLike.OPEN_FOR_PREVIEW.contains(item.state())) {
            ShelfLabelRules.requireItemTransition(item.state(), "PREVIEW_READY");
            transition(item, "PREVIEW_READY", null);
        }
        appendEvent(tenantId, item.taskId(), item.itemId(), "SHELF_LABEL_PREVIEWED", idempotencyKey,
            commandHash, json(Map.of("templateId", template.templateId(), "previewSha256", previewHash)), correlationId);
        repository.refreshTaskProjection(tenantId, item.taskId(), clock.instant());
        auditService.append("SHELF_LABEL_PREVIEWED", "SHELF_LABEL_TASK_ITEM", itemId,
            null, Map.of("previewSha256", previewHash), Map.of("templateId", template.templateId()));
        return new PreviewView(template.templateId(), template.versionNo(), template.contentSha256(),
            requireItem(tenantId, itemId), rendered, previewHash);
    }

    /** 受权人员确认完成货架换签；该状态不代表打印成功。 */
    @Transactional
    public TaskItemView confirmReplacement(Long itemId, int expectedVersion, String reason,
                                           String idempotencyKey, String correlationId) {
        return transitionCommand(itemId, expectedVersion, "REPLACED_CONFIRMED", null,
            "SHELF_LABEL_REPLACEMENT_CONFIRMED", reason, idempotencyKey, correlationId);
    }

    /** 记录任务项异常，历史事实只追加。 */
    @Transactional
    public TaskItemView markException(Long itemId, int expectedVersion, String reason,
                                      String idempotencyKey, String correlationId) {
        String normalized = requireReason(reason);
        TaskItemView result = transitionCommand(itemId, expectedVersion, "EXCEPTION", normalized,
            "SHELF_LABEL_EXCEPTION_RECORDED", normalized, idempotencyKey, correlationId);
        String tenantId = tenantContext.requireTenantId();
        appendException(tenantId, result.taskId(), result.itemId(), "MANUAL_EXCEPTION", normalized,
            "OPEN", correlationId);
        return result;
    }

    /** 调用失败关闭打印端口；任何不可用结果都会形成异常和 DISPATCH_BLOCKED。 */
    @Transactional
    public TaskView dispatch(Long taskId, String previewSha256, int expectedVersion,
                             String idempotencyKey, String correlationId) {
        String tenantId = tenantContext.requireTenantId();
        requireCommandIdentity(idempotencyKey, correlationId);
        TaskView task = requireTask(tenantId, taskId);
        storePort.requireAccessibleStore(task.storeId());
        String hash = ShelfLabelRules.sha256("DISPATCH|" + taskId + "|" + previewSha256 + "|" + expectedVersion);
        StoredCommand replay = repository.findCommand(tenantId, idempotencyKey);
        if (replay != null) {
            if (!hash.equals(replay.commandSha256())) throw idempotencyConflict();
            return task;
        }
        ShelfLabelPrintPort.DispatchResult result = printPort.dispatch(taskId, previewSha256);
        if (result.accepted()) throw new ServiceException("LBL-PRN-002: 未解阻打印端口不得返回成功", 500);
        if (repository.markTaskDispatchBlocked(tenantId, taskId, expectedVersion, clock.instant()) != 1) {
            throw new ServiceException("LBL-STATE-002: 价签任务版本冲突或已终结", 409);
        }
        appendException(tenantId, taskId, null, result.code(), result.message(), "BLOCKED_EXTERNAL", correlationId);
        appendEvent(tenantId, taskId, null, "SHELF_LABEL_DISPATCH_BLOCKED", idempotencyKey, hash,
            json(Map.of("code", result.code(), "previewSha256", previewSha256)), correlationId);
        auditService.append("SHELF_LABEL_DISPATCH_BLOCKED", "SHELF_LABEL_TASK", taskId,
            task, null, Map.of("code", result.code(), "printerEvidence", "BLOCKED"));
        return requireTask(tenantId, taskId);
    }

    private Long generateStoreTask(String tenantId, PriceBookEvent event, StoreSnapshot store,
                                   List<PriceSource> sources) {
        String sourceKey = sourceKey(event, store.storeId());
        String sourceHash = ShelfLabelRules.sha256(event.eventType() + "|" + event.priceBookId() + "|"
            + event.priceVersion() + "|" + event.contentSha256() + "|" + store.storeId());
        StoredTask existing = repository.findTaskBySource(tenantId, sourceKey);
        if (existing != null) {
            if (!sourceHash.equals(existing.sourceEventSha256())) {
                throw new ServiceException("LBL-IDEMPOTENCY-CONFLICT: 同来源键内容摘要不一致", 409);
            }
            return existing.taskId();
        }
        Instant effectiveAt = sources.stream().map(PriceSource::effectiveFrom).min(Comparator.naturalOrder())
            .orElse(event.occurredAt());
        Long taskId = IdWorker.getId();
        repository.insertTask(new GeneratedTask(taskId, tenantId, sourceKey, event.eventType(), sourceHash,
            event.priceBookId(), event.priceVersion(), store.storeId(), store.storeName(), effectiveAt,
            "PENDING", event.occurredAt()));
        for (PriceSource source : sources) generateItem(tenantId, taskId, event, store, source);
        repository.refreshTaskProjection(tenantId, taskId, event.occurredAt());
        appendEvent(tenantId, taskId, null, "SHELF_LABEL_TASK_CREATED", sourceKey, sourceHash,
            json(Map.of("priceBookId", event.priceBookId(), "priceVersion", event.priceVersion(),
                "storeId", store.storeId(), "itemCount", sources.size())), correlationFor(sourceKey));
        auditService.append("SHELF_LABEL_TASK_CREATED", "SHELF_LABEL_TASK", taskId,
            null, Map.of("sourceEventKey", sourceKey), Map.of("storeId", store.storeId(), "itemCount", sources.size()));
        return taskId;
    }

    private void generateItem(String tenantId, Long taskId, PriceBookEvent event,
                              StoreSnapshot store, PriceSource source) {
        boolean retired = "PRICE_BOOK_RETIRED".equals(event.eventType());
        Long resolved = sourcePort.resolveAmount(tenantId, source.skuId(), source.unitId(), store.storeId(),
            source.effectiveFrom(), event.priceBookId());
        Long oldAmount = retired ? source.amountMinor() : resolved;
        Long newAmount = retired ? resolved : source.amountMinor();
        int priority = "STORE".equals(event.scopeType()) ? 2 : 1;
        Long itemId = IdWorker.getId();
        String state = newAmount == null ? "EXCEPTION" : "PENDING";
        String reason = newAmount == null ? "价格版本停用后没有可回退的有效价格" : null;
        LatestOpenItem latest = repository.findLatestOpenItem(tenantId, store.storeId(), source.skuId(), source.unitId());
        List<Long> affectedTaskIds = repository.findOpenTaskIds(tenantId, store.storeId(), source.skuId(), source.unitId());
        if (latest != null && !ShelfLabelRules.isNewer(source.effectiveFrom(), priority, event.priceVersion(),
            event.priceBookId(), latest.effectiveAt(), latest.scopePriority(), latest.sourcePriceVersion(),
            latest.sourcePriceBookId())) {
            state = "SUPERSEDED";
            reason = null;
        }
        String snapshot = String.join("|", String.valueOf(event.priceBookId()), String.valueOf(source.priceItemId()),
            String.valueOf(event.priceVersion()), String.valueOf(store.storeId()), String.valueOf(source.skuId()),
            String.valueOf(source.unitId()), Objects.toString(source.barcode(), ""), Objects.toString(oldAmount, ""),
            Objects.toString(newAmount, ""), source.currency(), source.effectiveFrom().toString());
        repository.insertItem(new GeneratedItem(itemId, tenantId, taskId, event.priceBookId(), source.priceItemId(),
            event.priceVersion(), priority, store.storeId(), store.storeName(), source.skuId(), source.skuCode(),
            source.productName(), source.unitId(), source.unitName(), source.barcode(), oldAmount, newAmount,
            source.currency(), source.effectiveFrom(), state, reason, ShelfLabelRules.sha256(snapshot), event.occurredAt()));
        if ("PENDING".equals(state)) {
            repository.supersedeOpenItems(tenantId, store.storeId(), source.skuId(), source.unitId(), itemId,
                source.effectiveFrom(), priority, event.priceVersion(), event.priceBookId(), event.occurredAt());
            affectedTaskIds.stream().filter(affectedTaskId -> !affectedTaskId.equals(taskId)).distinct()
                .forEach(affectedTaskId -> repository.refreshTaskProjection(tenantId, affectedTaskId, event.occurredAt()));
        } else if ("EXCEPTION".equals(state)) {
            appendException(tenantId, taskId, itemId, "NO_FALLBACK_PRICE", reason, "OPEN", correlationFor(snapshot));
        }
    }

    private TaskItemView transitionCommand(Long itemId, int expectedVersion, String targetState,
                                           String exceptionReason, String eventType, String reason,
                                           String idempotencyKey, String correlationId) {
        String tenantId = tenantContext.requireTenantId();
        requireCommandIdentity(idempotencyKey, correlationId);
        TaskItemView item = requireItem(tenantId, itemId);
        storePort.requireAccessibleStore(item.storeId());
        String commandHash = ShelfLabelRules.sha256(eventType + "|" + itemId + "|" + expectedVersion + "|"
            + Objects.toString(reason, ""));
        StoredCommand existing = repository.findCommand(tenantId, idempotencyKey);
        if (existing != null) {
            if (!commandHash.equals(existing.commandSha256())) throw idempotencyConflict();
            return requireItem(tenantId, itemId);
        }
        ShelfLabelRules.requireItemTransition(item.state(), targetState);
        if (item.version() != expectedVersion || repository.transitionItem(tenantId, itemId, item.state(),
            expectedVersion, targetState, exceptionReason, clock.instant()) != 1) {
            throw new ServiceException("LBL-STATE-003: 价签任务项版本冲突", 409);
        }
        appendEvent(tenantId, item.taskId(), itemId, eventType, idempotencyKey, commandHash,
            json(Map.of("targetState", targetState, "reason", Objects.toString(reason, ""))), correlationId);
        repository.refreshTaskProjection(tenantId, item.taskId(), clock.instant());
        TaskItemView result = requireItem(tenantId, itemId);
        auditService.append(eventType, "SHELF_LABEL_TASK_ITEM", itemId, item, result,
            Map.of("reason", Objects.toString(reason, "")));
        return result;
    }

    private void recordPreviewFailure(String tenantId, TaskItemView item, String reason, String idempotencyKey,
                                      String commandHash, String correlationId) {
        if (SetLike.OPEN_FOR_FAILURE.contains(item.state())) {
            repository.transitionItem(tenantId, item.itemId(), item.state(), item.version(), "EXCEPTION",
                truncate(reason, 500), clock.instant());
            repository.refreshTaskProjection(tenantId, item.taskId(), clock.instant());
        }
        appendException(tenantId, item.taskId(), item.itemId(), "PREVIEW_FAILED", truncate(reason, 500),
            "OPEN", correlationId);
        appendEvent(tenantId, item.taskId(), item.itemId(), "SHELF_LABEL_PREVIEW_FAILED", idempotencyKey,
            commandHash, json(Map.of("reason", truncate(reason, 500))), correlationId);
    }

    private void transition(TaskItemView item, String target, String exceptionReason) {
        if (repository.transitionItem(tenantContext.requireTenantId(), item.itemId(), item.state(), item.version(),
            target, exceptionReason, clock.instant()) != 1) {
            throw new ServiceException("LBL-STATE-003: 价签任务项版本冲突", 409);
        }
    }

    private List<StoreSnapshot> targetStores(PriceBookEvent event) {
        return "STORE".equals(event.scopeType())
            ? List.of(storePort.requireAccessibleStore(event.storeId()))
            : storePort.listAccessibleActiveStores();
    }

    private Map<String, String> previewValues(TaskItemView item) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("productName", item.productName());
        values.put("skuCode", item.skuCode());
        values.put("barcode", Objects.toString(item.barcode(), "无条码"));
        values.put("unitName", item.unitName());
        values.put("oldPrice", item.oldPriceMinor() == null ? "首次定价" : formatMinor(item.oldPriceMinor()));
        values.put("newPrice", item.newPriceMinor() == null ? "无有效价" : formatMinor(item.newPriceMinor()));
        values.put("storeName", item.storeName());
        values.put("priceVersion", String.valueOf(item.sourcePriceVersion()));
        values.put("effectiveAt", item.effectiveAt().toString());
        values.put("taskStatus", item.state());
        values.put("exceptionReason", Objects.toString(item.exceptionReason(), ""));
        return values;
    }

    private TemplateView requirePublishedTemplate(String tenantId, Long requestedId, Long storeId) {
        TemplateView template = requestedId == null
            ? repository.findPublishedTemplate(tenantId, storeId)
            : requireTemplate(tenantId, requestedId);
        if (template == null || !"PUBLISHED".equals(template.state())
            || "STORE".equals(template.scopeType()) && !storeId.equals(template.storeId())) {
            throw new ServiceException("LBL-TPL-010: 没有可用的已发布价签模板", 404);
        }
        requireTemplateAccess(template);
        return template;
    }

    private TemplateView requireTemplate(String tenantId, Long templateId) {
        TemplateView template = repository.findTemplate(tenantId, templateId);
        if (template == null) throw new ServiceException("LBL-TPL-011: 价签模板不存在或不可见", 404);
        return template;
    }

    private TaskView requireTask(String tenantId, Long taskId) {
        TaskView task = repository.findTask(tenantId, taskId);
        if (task == null) throw new ServiceException("LBL-TASK-001: 价签任务不存在或不可见", 404);
        return task;
    }

    private TaskItemView requireItem(String tenantId, Long itemId) {
        TaskItemView item = repository.findTaskItem(tenantId, itemId);
        if (item == null) throw new ServiceException("LBL-TASK-002: 价签任务项不存在或不可见", 404);
        return item;
    }

    private void requireTemplateAccess(TemplateView template) {
        if (template.storeId() != null) storePort.requireAccessibleStore(template.storeId());
    }

    private String requireTemplateScope(String value, Long storeId) {
        String scope = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!(("TENANT".equals(scope) && storeId == null) || ("STORE".equals(scope) && storeId != null))) {
            throw new ServiceException("LBL-TPL-012: 价签模板作用域形状无效", 400);
        }
        return scope;
    }

    private void validatePriceEvent(PriceBookEvent event) {
        if (event == null || !SetLike.PRICE_EVENTS.contains(event.eventType()) || event.priceBookId() == null
            || event.priceVersion() == null || event.priceVersion() <= 0 || event.contentSha256() == null
            || !event.contentSha256().matches("[a-f0-9]{64}") || event.occurredAt() == null
            || !("TENANT_BASE".equals(event.scopeType()) && event.storeId() == null
            || "STORE".equals(event.scopeType()) && event.storeId() != null)) {
            throw new ServiceException("LBL-SRC-002: 价格来源事件不完整或非法", 400);
        }
    }

    private ServiceException idempotencyConflict() {
        return new ServiceException("LBL-IDEMPOTENCY-CONFLICT: 同幂等键内容摘要不一致", 409);
    }

    private void requireCommandIdentity(String idempotencyKey, String correlationId) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ServiceException("LBL-IDEM-001: 幂等键格式无效", 400);
        }
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 96) {
            throw new ServiceException("LBL-OBS-001: 关联标识为空或过长", 400);
        }
    }

    private String requireReason(String value) {
        String reason = value == null ? "" : value.trim();
        if (reason.isEmpty() || reason.length() > 500) throw new ServiceException("LBL-TASK-003: 原因为空或过长", 400);
        return reason;
    }

    private void appendEvent(String tenantId, Long taskId, Long itemId, String eventType, String idempotencyKey,
                             String commandHash, String payloadJson, String correlationId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        repository.appendEvent(new LabelEvent(IdWorker.getId(), tenantId, taskId, itemId, eventType,
            idempotencyKey, commandHash, ShelfLabelRules.sha256(payloadJson), payloadJson,
            principal.userId(), correlationId, clock.instant()));
    }

    private void appendException(String tenantId, Long taskId, Long itemId, String code, String reason,
                                 String resolutionType, String correlationId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        repository.appendException(new LabelException(IdWorker.getId(), tenantId, taskId, itemId, code,
            truncate(reason, 500), resolutionType, principal.userId(), correlationId, clock.instant()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("价签证据序列化失败", exception);
        }
    }

    private String sourceKey(PriceBookEvent event, Long storeId) {
        return ("PRICE_BOOK_PUBLISHED".equals(event.eventType()) ? "price-book.published.v1:" : "price-book.retired.v1:")
            + event.priceBookId() + ":" + event.priceVersion() + ":" + storeId;
    }

    private String correlationFor(String value) {
        return "lbl-" + ShelfLabelRules.sha256(value).substring(0, 26);
    }

    private String formatMinor(long amount) {
        return String.format(Locale.ROOT, "%d.%02d", amount / 100, Math.abs(amount % 100));
    }

    private String truncate(String value, int max) {
        String text = value == null ? "未知异常" : value;
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 只在服务内部集中声明允许集合，避免散落的魔法字符串。 */
    private static final class SetLike {
        private static final java.util.Set<String> PRICE_EVENTS = java.util.Set.of("PRICE_BOOK_PUBLISHED", "PRICE_BOOK_RETIRED");
        private static final java.util.Set<String> OPEN_FOR_PREVIEW = java.util.Set.of("PENDING", "EXCEPTION");
        private static final java.util.Set<String> OPEN_FOR_FAILURE = java.util.Set.of("PENDING", "PREVIEW_READY");

        private SetLike() {
        }
    }
}
