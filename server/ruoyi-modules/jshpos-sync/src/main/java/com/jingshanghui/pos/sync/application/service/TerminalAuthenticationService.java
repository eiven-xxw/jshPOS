package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.sync.application.model.TerminalModels.AuthenticateTerminalCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.AuthenticatedDevice;
import com.jingshanghui.pos.sync.application.model.TerminalModels.CredentialRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.DeviceAuthRecord;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort;
import com.jingshanghui.pos.foundation.application.port.TrustedDeviceStoreContextPort;
import com.jingshanghui.pos.foundation.application.context.VerifiedDeviceTenantScope;
import com.jingshanghui.pos.sync.domain.SyncHash;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.domain.SyncRules;
import com.jingshanghui.pos.sync.domain.TerminalRules;
import com.jingshanghui.pos.sync.domain.TerminalSecretProtector;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 将 deviceId + 一次性返回的设备凭据换成可信终端上下文。
 * 该服务不信任客户端 tenant/store/capability 声明，也不签发用户会话。
 */
@Service
@RequiredArgsConstructor
public class TerminalAuthenticationService {
    private final TerminalRegistryPort port;
    private final TerminalSecretProtector secretProtector;
    private final SyncIdGenerator idGenerator;
    private final TrustedDeviceStoreContextPort storeContextPort;
    private final VerifiedDeviceTenantScope deviceTenantScope;
    private final Clock clock;

    // 认证拒绝仍需提交克隆阻断与安全审计；本服务在抛错前不写任何业务成功事实。
    @Transactional(noRollbackFor = ServiceException.class)
    public AuthenticatedDevice authenticate(AuthenticateTerminalCommand command) {
        SyncRules.requireUlid(command.deviceId(), "deviceId");
        TerminalRules.requireSha256(command.deviceFingerprintSha256(), "deviceFingerprintSha256");
        TerminalRules.requireSha256(command.publicKeySha256(), "publicKeySha256");
        TerminalRules.requireVersion(command.appVersion(), "appVersion");
        TerminalRules.requireVersion(command.protocolVersion(), "protocolVersion");
        TerminalRules.requireVersion(command.schemaVersion(), "schemaVersion");
        long skew = TerminalRules.clockSkewSeconds(command.clientTime(), clock.instant());
        TerminalRules.requireClockSkew(skew);
        DeviceAuthRecord device = port.lockDeviceForAuthentication(command.deviceId());
        if (device == null) throw unauthorized();
        CredentialRecord credential = port.findActiveCredential(device.tenantId(), device.deviceId());
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        boolean cloned = credential != null && (!credential.fingerprintSha256().equals(command.deviceFingerprintSha256())
            || !credential.publicKeySha256().equals(command.publicKeySha256()));
        if (cloned) {
            if ("ACTIVE".equals(device.status())) {
                port.changeStatus(new TerminalRegistryPort.StatusChange(device.tenantId(), device.deviceId(),
                    "ACTIVE", "BLOCKED", "检测到凭据克隆或硬件身份变化", device.recordVersion(), now));
            }
            appendRejected(device, "CREDENTIAL_CLONE_REJECTED", "凭据克隆或硬件身份变化", now);
            throw new ServiceException("TRM_CREDENTIAL_CLONED: 终端已阻断并等待安全复核", 423);
        }
        if (!"ACTIVE".equals(device.status()) || credential == null || !"ACTIVE".equals(credential.status())
            || !credential.expiresAt().isAfter(now)
            || !secretProtector.matches("credential:" + device.deviceId() + ":" + credential.credentialVersion(),
                command.deviceCredential(), credential.secretHmac())) {
            appendRejected(device, "CREDENTIAL_REJECTED", "凭据、状态或有效期校验失败", now);
            throw unauthorized();
        }
        if (TerminalRules.compareVersion(command.appVersion(), device.appVersion()) < 0
            || TerminalRules.compareVersion(command.schemaVersion(), device.schemaVersion()) < 0
            || TerminalRules.compareVersion(command.protocolVersion(), device.minProtocolVersion()) < 0
            || TerminalRules.compareVersion(command.protocolVersion(), device.maxProtocolVersion()) > 0) {
            appendRejected(device, "VERSION_REJECTED", "应用、协议或 Schema 兼容窗口失败", now);
            throw new ServiceException("TRM_VERSION_REJECTED: 终端版本不在兼容窗口", 409);
        }
        var identity = new VerifiedDeviceTenantScope.DeviceIdentity(device.tenantId(), device.orgUnitId(),
            device.storeId(), device.deviceId());
        var store = deviceTenantScope.execute(identity,
            () -> storeContextPort.resolve(device.tenantId(), device.orgUnitId(), device.storeId(), clock.instant()));
        return new AuthenticatedDevice(device.tenantId(), device.deviceId(), device.orgUnitId(), device.storeId(),
            device.terminalId(), device.boundUserId(), store.storeName(), store.zoneId(), store.businessDate(),
            "终端 " + device.terminalId(), device.status(), command.protocolVersion(), command.schemaVersion(),
            credential.credentialVersion(), credential.expiresAt().toInstant(ZoneOffset.UTC));
    }

    private void appendRejected(DeviceAuthRecord device, String action, String reason, LocalDateTime now) {
        port.insertAudit(new TerminalRegistryPort.AuditWrite(idGenerator.next(), device.tenantId(), device.deviceId(),
            device.storeId(), action, device.status(), device.status(), SyncHash.evidence(device.deviceId(), action),
            "DEVICE", device.deviceId(), reason, idGenerator.next(), now));
    }

    private ServiceException unauthorized() {
        return new ServiceException("TRM_CREDENTIAL_INVALID: 设备身份验证失败", 401);
    }
}
