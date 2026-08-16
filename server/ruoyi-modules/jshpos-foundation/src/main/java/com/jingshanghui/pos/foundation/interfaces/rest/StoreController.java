package com.jingshanghui.pos.foundation.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.BusinessDateView;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.StoreView;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.foundation.interfaces.rest.dto.FoundationRequests.CreateStore;
import com.jingshanghui.pos.foundation.interfaces.rest.dto.FoundationRequests.UpdateStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/foundation/stores")
public class StoreController {

    private final StoreService service;

    @GetMapping
    @SaCheckPermission("foundation:store:query")
    public R<List<StoreView>> list() {
        return R.ok(service.list());
    }

    @PostMapping
    @SaCheckPermission("foundation:store:manage")
    @Log(title = "门店", businessType = BusinessType.INSERT)
    public R<StoreView> create(@Valid @RequestBody CreateStore request) {
        return R.ok(service.create(new StoreService.CreateStore(
            request.orgUnitId(), request.platformDeptId(), request.code(), request.name(),
            request.zoneId(), request.businessDayStart()
        )));
    }

    @PutMapping("/{storeId}")
    @SaCheckPermission("foundation:store:manage")
    @Log(title = "门店", businessType = BusinessType.UPDATE)
    public R<StoreView> update(@PathVariable Long storeId, @Valid @RequestBody UpdateStore request) {
        return R.ok(service.update(storeId, new StoreService.UpdateStore(
            request.orgUnitId(), request.platformDeptId(), request.code(), request.name(), request.zoneId(),
            request.businessDayStart(), request.status(), request.version()
        )));
    }

    @GetMapping("/{storeId}/business-date")
    @SaCheckPermission("foundation:store:query")
    public R<BusinessDateView> businessDate(
        @PathVariable Long storeId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at
    ) {
        return R.ok(service.businessDate(storeId, at));
    }
}
