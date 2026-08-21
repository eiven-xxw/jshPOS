package com.jingshanghui.pos.sync.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import com.jingshanghui.pos.sync.application.model.TerminalModels.ActivateTerminalCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.AuthenticateTerminalCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.ReportCapabilityCommand;
import com.jingshanghui.pos.sync.application.service.TerminalAuthenticationService;
import com.jingshanghui.pos.sync.application.service.TerminalRegistryService;
import com.jingshanghui.pos.sync.interfaces.rest.dto.TerminalRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POS 终端激活与能力上报入口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pos/v1/terminals")
public class PosTerminalController {
    private final TerminalRegistryService service;
    private final TerminalAuthenticationService authenticationService;

    @SaIgnore
    @PostMapping("/activate")
    public ResponseEntity<R<?>> activate(@Valid @RequestBody TerminalRequests.Activate request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(R.ok(service.activate(new ActivateTerminalCommand(
            request.activationId(), request.activationSecret(),
            request.deviceFingerprintSha256(), request.publicKeySha256(), request.appVersion(),
            request.protocolVersion(), request.schemaVersion(), request.capability(), request.idempotencyKey(),
            request.clientTime()))));
    }

    /** 使用服务端登记凭据换取可信设备上下文，不创建或替代员工会话。 */
    @SaIgnore
    @PostMapping("/authenticate")
    public ResponseEntity<R<?>> authenticate(@Valid @RequestBody TerminalRequests.Authenticate request) {
        return ResponseEntity.ok(R.ok(authenticationService.authenticate(new AuthenticateTerminalCommand(
            request.deviceId(), request.deviceCredential(), request.deviceFingerprintSha256(),
            request.publicKeySha256(), request.appVersion(), request.protocolVersion(), request.schemaVersion(),
            request.clientTime()))));
    }

    @PostMapping("/{deviceId}/capabilities")
    @SaCheckPermission("pos:terminal:report")
    public ResponseEntity<R<?>> capability(@PathVariable @Pattern(regexp = TerminalRequests.ULID) String deviceId,
                                            @Valid @RequestBody TerminalRequests.Capability request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(R.ok(service.reportCapability(
            new ReportCapabilityCommand(deviceId, request.appVersion(),
            request.protocolVersion(), request.schemaVersion(), request.capability(), request.idempotencyKey(),
            request.clientTime()))));
    }
}
