package com.jingshanghui.pos.foundation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.OrgUnitView;
import com.jingshanghui.pos.foundation.application.service.OrgUnitService;
import com.jingshanghui.pos.foundation.interfaces.rest.dto.FoundationRequests.CreateOrgUnit;
import com.jingshanghui.pos.foundation.interfaces.rest.dto.FoundationRequests.UpdateOrgUnit;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/foundation/org-units")
public class OrgUnitController {

    private final OrgUnitService service;

    @GetMapping
    @SaCheckPermission("foundation:org:query")
    public R<List<OrgUnitView>> list() {
        return R.ok(service.list());
    }

    @PostMapping
    @SaCheckPermission("foundation:org:manage")
    @Log(title = "业务组织", businessType = BusinessType.INSERT)
    public R<OrgUnitView> create(@Valid @RequestBody CreateOrgUnit request) {
        return R.ok(service.create(new OrgUnitService.CreateOrgUnit(
            request.parentId(), request.code(), request.name(), request.type()
        )));
    }

    @PutMapping("/{orgUnitId}")
    @SaCheckPermission("foundation:org:manage")
    @Log(title = "业务组织", businessType = BusinessType.UPDATE)
    public R<OrgUnitView> update(@PathVariable Long orgUnitId, @Valid @RequestBody UpdateOrgUnit request) {
        return R.ok(service.update(orgUnitId, new OrgUnitService.UpdateOrgUnit(
            request.parentId(), request.code(), request.name(), request.type(), request.status(), request.version()
        )));
    }
}
