package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceRecord;
import com.jingshanghui.pos.sync.domain.SyncRules;
import com.jingshanghui.pos.sync.domain.TerminalRules;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class SyncDeviceContextService {

    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final SyncMapper mapper;
    private final Clock clock;

    public DeviceContext require(String requestedDeviceId, String protocolVersion) {
        SyncRules.requireUlid(requestedDeviceId, "deviceId");
        if (!"1.0".equals(protocolVersion)) {
            throw new ServiceException("SYNC_PROTOCOL_UNSUPPORTED: supported protocol is 1.0", 409);
        }
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        DeviceRecord device = mapper.findDevice(principal.tenantId(), requestedDeviceId);
        if (device == null || !principal.userId().equals(device.boundUserId())) {
            throw new ServiceException("SYNC_DEVICE_NOT_AUTHORIZED: device binding is not visible", 401);
        }
        if ("BLOCKED".equals(device.status())) {
            throw new ServiceException("DEVICE_BLOCKED: device requires security review", 423);
        }
        if (!"ACTIVE".equals(device.status())) {
            throw new ServiceException("SYNC_DEVICE_NOT_AUTHORIZED: device is not active", 401);
        }
        if (TerminalRules.compareVersion(protocolVersion, device.minProtocolVersion()) < 0
            || TerminalRules.compareVersion(protocolVersion, device.maxProtocolVersion()) > 0) {
            throw new ServiceException("SYNC_PROTOCOL_UNSUPPORTED: device compatibility window rejected", 409);
        }
        authorizationService.requireStoreAccess(device.storeId());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (mapper.touchDevice(principal.tenantId(), requestedDeviceId, now) != 1) {
            throw new ServiceException("DEVICE_BLOCKED: device status changed", 423);
        }
        return new DeviceContext(principal.tenantId(), device.deviceId(), device.storeId(),
            device.terminalId(), principal.userId(), protocolVersion);
    }
}
