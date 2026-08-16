package com.jingshanghui.pos.foundation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.ConfigBindingView;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.ConfigTemplateView;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.ConfigVersionView;
import com.jingshanghui.pos.foundation.application.service.ConfigGovernanceService;
import com.jingshanghui.pos.foundation.interfaces.rest.dto.FoundationRequests.ActivateConfig;
import com.jingshanghui.pos.foundation.interfaces.rest.dto.FoundationRequests.CreateConfigTemplate;
import com.jingshanghui.pos.foundation.interfaces.rest.dto.FoundationRequests.CreateConfigVersion;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/foundation/config")
public class ConfigGovernanceController {

    private final ConfigGovernanceService service;

    @GetMapping("/templates")
    @SaCheckPermission("foundation:config:query")
    public R<List<ConfigTemplateView>> listTemplates() {
        return R.ok(service.listTemplates());
    }

    @PostMapping("/templates")
    @SaCheckPermission("foundation:config:manage")
    @Log(title = "行业配置模板", businessType = BusinessType.INSERT)
    public R<ConfigTemplateView> createTemplate(@Valid @RequestBody CreateConfigTemplate request) {
        return R.ok(service.createTemplate(new ConfigGovernanceService.CreateTemplate(
            request.code(), request.name(), request.industry()
        )));
    }

    @PostMapping("/templates/{templateId}/versions")
    @SaCheckPermission("foundation:config:manage")
    @Log(title = "配置模板版本", businessType = BusinessType.INSERT,
        isSaveRequestData = false, isSaveResponseData = false)
    public R<ConfigVersionView> createVersion(
        @PathVariable Long templateId,
        @Valid @RequestBody CreateConfigVersion request
    ) {
        return R.ok(service.createVersion(templateId, new ConfigGovernanceService.CreateVersion(
            request.schemaVersion(), request.content()
        )));
    }

    @PostMapping("/versions/{versionId}/publish")
    @SaCheckPermission("foundation:config:publish")
    @Log(title = "配置版本发布", businessType = BusinessType.UPDATE)
    public R<ConfigVersionView> publish(@PathVariable Long versionId) {
        return R.ok(service.publish(versionId));
    }

    @PostMapping("/bindings/activate")
    @SaCheckPermission("foundation:config:activate")
    @Log(title = "配置版本激活", businessType = BusinessType.UPDATE)
    public R<ConfigBindingView> activate(@Valid @RequestBody ActivateConfig request) {
        return R.ok(service.activate(new ConfigGovernanceService.ActivateConfig(
            request.templateId(), request.configVersionId(), request.targetType(), request.targetId()
        )));
    }

    @PostMapping("/bindings/{bindingId}/rollback")
    @SaCheckPermission("foundation:config:activate")
    @Log(title = "配置版本回退", businessType = BusinessType.UPDATE)
    public R<ConfigBindingView> rollback(@PathVariable Long bindingId) {
        return R.ok(service.rollback(bindingId));
    }
}
