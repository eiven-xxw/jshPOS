package com.jingshanghui.pos.sync.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.sync.application.model.TerminalModels.ChangeTerminalStatusCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.IssueActivationCommand;
import com.jingshanghui.pos.sync.application.service.TerminalRegistryService;
import com.jingshanghui.pos.sync.interfaces.rest.dto.TerminalRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 终端后台控制面；所有门店范围由服务端可信上下文再次校验。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class TerminalAdminController {
    private final TerminalRegistryService service;

    @PostMapping("/terminal-activations")
    @SaCheckPermission("terminal:activation:issue")
    @Log(title = "签发终端激活", businessType = BusinessType.INSERT,
        isSaveRequestData = false, isSaveResponseData = false)
    public ResponseEntity<R<?>> issue(@Valid @RequestBody TerminalRequests.IssueActivation request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(R.ok(service.issue(new IssueActivationCommand(
            request.orgUnitId(), request.storeId(), request.boundUserId(), request.terminalProfileCode(),
            request.expiresInSeconds(), request.idempotencyKey()))));
    }

    @PostMapping("/terminal-activations/{activationId}/cancel")
    @SaCheckPermission("terminal:activation:cancel")
    @Log(title = "取消终端激活", businessType = BusinessType.UPDATE)
    public ResponseEntity<Void> cancel(@PathVariable @Pattern(regexp = TerminalRequests.ULID) String activationId) {
        service.cancel(activationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/terminals")
    @SaCheckPermission("terminal:registry:read")
    public R<?> list(@RequestParam(required = false) @Min(1) Long storeId,
                     @RequestParam(defaultValue = "1") @Min(1) int page,
                     @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return R.ok(service.list(storeId, page, size));
    }

    @PutMapping("/terminals/{deviceId}/status")
    @SaCheckPermission("terminal:status:manage")
    @Log(title = "变更终端安全状态", businessType = BusinessType.UPDATE)
    public R<?> status(@PathVariable @Pattern(regexp = TerminalRequests.ULID) String deviceId,
                       @Valid @RequestBody TerminalRequests.ChangeStatus request) {
        return R.ok(service.changeStatus(deviceId, new ChangeTerminalStatusCommand(request.targetStatus(),
            request.reason(), request.idempotencyKey(), request.expectedVersion())));
    }

    @PostMapping("/terminals/{deviceId}/credentials/rotate")
    @SaCheckPermission("terminal:credential:rotate")
    @Log(title = "轮换终端凭据", businessType = BusinessType.UPDATE,
        isSaveRequestData = false, isSaveResponseData = false)
    public ResponseEntity<R<?>> rotate(
        @PathVariable @Pattern(regexp = TerminalRequests.ULID) String deviceId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{15,63}$") String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).body(R.ok(service.rotateCredential(deviceId, idempotencyKey)));
    }
}
