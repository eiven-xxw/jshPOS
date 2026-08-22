package com.jingshanghui.pos.catalog.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.CreateTemplateCommand;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.PreviewView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskDetailView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskItemView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TemplateView;
import com.jingshanghui.pos.catalog.application.service.ShelfLabelService;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.ShelfLabelRequests.Confirm;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.ShelfLabelRequests.CreateTemplate;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.ShelfLabelRequests.Dispatch;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.ShelfLabelRequests.Preview;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.ShelfLabelRequests.RecordException;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.ShelfLabelRequests.Versioned;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 货架价签协议适配层；只负责校验、权限和响应转换。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/catalog/shelf-labels")
public class ShelfLabelController {

    private final ShelfLabelService service;

    @PostMapping("/templates")
    @SaCheckPermission("catalog:label:template:manage")
    @Log(title = "货架价签模板", businessType = BusinessType.INSERT)
    public R<TemplateView> createTemplate(@Valid @RequestBody CreateTemplate request) {
        return R.ok(service.createTemplate(new CreateTemplateCommand(request.templateCode(), request.templateName(),
            request.versionNo(), request.scopeType(), request.storeId(), request.bodyTemplate(),
            request.idempotencyKey(), request.correlationId())));
    }

    @PostMapping("/templates/{templateId}/publish")
    @SaCheckPermission("catalog:label:template:publish")
    @Log(title = "货架价签模板发布", businessType = BusinessType.UPDATE)
    public R<TemplateView> publishTemplate(@PathVariable Long templateId, @Valid @RequestBody Versioned request) {
        return R.ok(service.publishTemplate(templateId, request.expectedVersion(), request.idempotencyKey(),
            request.correlationId()));
    }

    @PostMapping("/templates/{templateId}/retire")
    @SaCheckPermission("catalog:label:template:publish")
    @Log(title = "货架价签模板停用", businessType = BusinessType.UPDATE)
    public R<TemplateView> retireTemplate(@PathVariable Long templateId, @Valid @RequestBody Versioned request) {
        return R.ok(service.retireTemplate(templateId, request.expectedVersion(), request.idempotencyKey(),
            request.correlationId()));
    }

    @GetMapping("/templates")
    @SaCheckPermission("catalog:label:task:read")
    public R<List<TemplateView>> listTemplates(@RequestParam(required = false) String state,
                                               @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return R.ok(service.listTemplates(state, limit));
    }

    @GetMapping("/tasks")
    @SaCheckPermission("catalog:label:task:read")
    public R<List<TaskView>> listTasks(@RequestParam(required = false) Long storeId,
                                       @RequestParam(required = false) String state,
                                       @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        return R.ok(service.listTasks(storeId, state, limit));
    }

    @GetMapping("/tasks/{taskId}")
    @SaCheckPermission("catalog:label:task:read")
    public R<TaskDetailView> detail(@PathVariable Long taskId) {
        return R.ok(service.detail(taskId));
    }

    @PostMapping("/items/{itemId}/preview")
    @SaCheckPermission("catalog:label:task:read")
    @Log(title = "货架价签预览", businessType = BusinessType.OTHER)
    public R<PreviewView> preview(@PathVariable Long itemId, @Valid @RequestBody Preview request) {
        return R.ok(service.preview(itemId, request.templateId(), request.idempotencyKey(), request.correlationId()));
    }

    @PostMapping("/items/{itemId}/confirm")
    @SaCheckPermission("catalog:label:task:confirm")
    @Log(title = "货架换签确认", businessType = BusinessType.UPDATE)
    public R<TaskItemView> confirm(@PathVariable Long itemId, @Valid @RequestBody Confirm request) {
        return R.ok(service.confirmReplacement(itemId, request.expectedVersion(), request.reason(),
            request.idempotencyKey(), request.correlationId()));
    }

    @PostMapping("/items/{itemId}/exceptions")
    @SaCheckPermission("catalog:label:task:exception")
    @Log(title = "货架价签异常", businessType = BusinessType.UPDATE)
    public R<TaskItemView> recordException(@PathVariable Long itemId,
                                           @Valid @RequestBody RecordException request) {
        return R.ok(service.markException(itemId, request.expectedVersion(), request.reason(),
            request.idempotencyKey(), request.correlationId()));
    }

    @PostMapping("/tasks/{taskId}/dispatch")
    @SaCheckPermission("catalog:label:task:dispatch")
    @Log(title = "货架价签打印失败关闭", businessType = BusinessType.OTHER)
    public R<TaskView> dispatch(@PathVariable Long taskId, @Valid @RequestBody Dispatch request) {
        return R.ok(service.dispatch(taskId, request.previewSha256(), request.expectedVersion(),
            request.idempotencyKey(), request.correlationId()));
    }
}
