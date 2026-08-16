package com.jingshanghui.pos.sync.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.sync.application.service.SyncRepairService;
import com.jingshanghui.pos.sync.interfaces.rest.dto.SyncRequests;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/v1/sync")
public class SyncAdminController {

    private final SyncRepairService repairService;

    @PostMapping("/dead-letters/{eventId}/retry")
    @SaCheckPermission("sync:repair")
    @Log(title = "同步死信修复", businessType = BusinessType.UPDATE)
    public R<Void> retry(@PathVariable @Pattern(regexp = SyncRequests.ULID) String eventId) {
        repairService.reopenDeadLetter(eventId);
        return R.ok();
    }
}
