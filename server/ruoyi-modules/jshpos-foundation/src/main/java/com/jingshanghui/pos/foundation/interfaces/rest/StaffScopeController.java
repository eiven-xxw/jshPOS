package com.jingshanghui.pos.foundation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.StaffScopeView;
import com.jingshanghui.pos.foundation.application.service.StaffScopeService;
import com.jingshanghui.pos.foundation.interfaces.rest.dto.FoundationRequests.ReplaceStaffScopes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/foundation/staff-scopes")
public class StaffScopeController {

    private final StaffScopeService service;

    @GetMapping("/{userId}")
    @SaCheckPermission("foundation:scope:query")
    public R<List<StaffScopeView>> list(@PathVariable Long userId) {
        return R.ok(service.list(userId));
    }

    @PutMapping("/{userId}")
    @SaCheckPermission("foundation:scope:grant")
    @Log(title = "员工数据范围", businessType = BusinessType.GRANT)
    public R<List<StaffScopeView>> replace(
        @PathVariable Long userId,
        @Valid @RequestBody ReplaceStaffScopes request
    ) {
        return R.ok(service.replace(userId, request.scopes().stream()
            .map(item -> new StaffScopeService.ScopeInput(item.scopeType(), item.orgUnitId(), item.storeId()))
            .toList()));
    }
}
