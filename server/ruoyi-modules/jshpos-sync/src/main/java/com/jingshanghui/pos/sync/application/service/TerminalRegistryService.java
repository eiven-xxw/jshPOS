package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.StoreView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.sync.application.model.TerminalModels.ActivateTerminalCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.ActivatedTerminal;
import com.jingshanghui.pos.sync.application.model.TerminalModels.ActivationRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.ChangeTerminalStatusCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.IssueActivationCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.IssuedActivation;
import com.jingshanghui.pos.sync.application.model.TerminalModels.ReportCapabilityCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.RotatedCredential;
import com.jingshanghui.pos.sync.application.model.TerminalModels.StoredCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.TerminalPage;
import com.jingshanghui.pos.sync.application.model.TerminalModels.TerminalAuditCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.TerminalView;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort;
import com.jingshanghui.pos.sync.domain.SyncHash;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.domain.SyncRules;
import com.jingshanghui.pos.sync.domain.TerminalHash;
import com.jingshanghui.pos.sync.domain.TerminalRules;
import com.jingshanghui.pos.sync.domain.TerminalSecretGenerator;
import com.jingshanghui.pos.sync.domain.TerminalSecretProtector;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** T2-TRM-001 终端登记、激活、状态、凭据和能力的唯一应用服务。 */
@Service
@RequiredArgsConstructor
public class TerminalRegistryService {
    private static final long MIN_ACTIVATION_SECONDS = 60;
    private static final long MAX_ACTIVATION_SECONDS = 86_400;
    private static final long CREDENTIAL_DAYS = 365;

    private final TerminalRegistryPort port;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final StoreService storeService;
    private final TerminalSecretProtector secretProtector;
    private final TerminalSecretGenerator secretGenerator;
    private final SyncIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 签发一次性激活秘密；幂等重放只返回授权元数据，不再次显示秘密。 */
    @Transactional
    public IssuedActivation issue(IssueActivationCommand command) {
        TrustedPrincipal actor = tenantContext.requirePrincipal();
        StoreView store = requireStore(command.storeId(), command.orgUnitId());
        requirePositive(command.boundUserId(), "boundUserId");
        if (command.expiresInSeconds() < MIN_ACTIVATION_SECONDS || command.expiresInSeconds() > MAX_ACTIVATION_SECONDS) {
            throw new ServiceException("TRM_INPUT_INVALID: 激活有效期必须为 60 至 86400 秒", 400);
        }
        String profile = TerminalRules.requireCode(command.terminalProfileCode(), "terminalProfileCode");
        String key = TerminalRules.requireIdempotencyKey(command.idempotencyKey());
        String requestHash = TerminalHash.digest(objectMapper, Map.of("org", store.orgUnitId(), "store", store.storeId(),
            "user", command.boundUserId(), "profile", profile, "expires", command.expiresInSeconds()));
        ActivationRecord previous = port.findActivationByCommand(actor.tenantId(), key);
        if (previous != null) {
            requireSameHash(previous.requestSha256(), requestHash);
            return new IssuedActivation(previous.activationId(), null, previous.expiresAt(), previous.status(), false);
        }
        LocalDateTime now = now();
        String activationId = idGenerator.next();
        String rawSecret = secretGenerator.next();
        LocalDateTime expiresAt = now.plusSeconds(command.expiresInSeconds());
        port.insertActivation(new TerminalRegistryPort.ActivationWrite(activationId, actor.tenantId(),
            store.orgUnitId(), store.storeId(), command.boundUserId(), profile,
            secretProtector.digest("activation:" + activationId, rawSecret), expiresAt, key, requestHash,
            "SYNTHETIC", actor.userId(), now));
        appendAudit(new TerminalAuditCommand(actor.tenantId(), null, store.storeId(), "ACTIVATION_ISSUED", null,
            "ISSUED", requestHash, "USER", actor.userId().toString(), "虚构终端激活授权", now));
        return new IssuedActivation(activationId, rawSecret, expiresAt, "ISSUED", true);
    }

    /** 无租户 Header 激活入口；授权记录是 tenant/org/store 的唯一来源。 */
    @Transactional
    public ActivatedTerminal activate(ActivateTerminalCommand command) {
        SyncRules.requireUlid(command.activationId(), "activationId");
        TerminalRules.requireSha256(command.deviceFingerprintSha256(), "deviceFingerprintSha256");
        TerminalRules.requireSha256(command.publicKeySha256(), "publicKeySha256");
        TerminalRules.requireVersion(command.appVersion(), "appVersion");
        TerminalRules.requireSupportedProtocol(command.protocolVersion());
        TerminalRules.requireVersion(command.schemaVersion(), "schemaVersion");
        String key = TerminalRules.requireIdempotencyKey(command.idempotencyKey());
        String capabilityJson = TerminalHash.canonicalJson(objectMapper, command.capability());
        String capabilityHash = SyncHash.evidence(capabilityJson);
        long skew = TerminalRules.clockSkewSeconds(command.clientTime(), clock.instant());
        TerminalRules.requireClockSkew(skew);
        String requestHash = TerminalHash.digest(objectMapper, Map.of(
            "activationId", command.activationId(), "fingerprint", command.deviceFingerprintSha256(),
            "publicKey", command.publicKeySha256(), "app", command.appVersion(), "protocol", command.protocolVersion(),
            "schema", command.schemaVersion(), "capability", capabilityHash, "clientTime", command.clientTime().toString()));
        ActivationRecord activation = port.lockActivationById(command.activationId());
        if (activation == null) throw new ServiceException("TRM_ACTIVATION_INVALID: 激活授权不存在", 401);
        StoredCommand previous = port.findCommand(activation.tenantId(), "ACTIVATE", key);
        if (previous != null) {
            requireSameHash(previous.requestSha256(), requestHash);
            TerminalView device = requireDevice(activation.tenantId(), previous.aggregateId());
            return activatedView(activation.tenantId(), device, null, false);
        }
        LocalDateTime now = now();
        if (!"ISSUED".equals(activation.status()) || !activation.expiresAt().isAfter(now)
            || !secretProtector.matches("activation:" + activation.activationId(), command.activationSecret(), activation.secretHmac())) {
            throw new ServiceException("TRM_ACTIVATION_INVALID: 激活授权无效、过期或已消费", 401);
        }
        String deviceId = idGenerator.next();
        String rawCredential = secretGenerator.next();
        port.insertDevice(new TerminalRegistryPort.DeviceWrite(deviceId, activation.tenantId(), activation.orgUnitId(),
            activation.storeId(), deviceId, activation.boundUserId(), activation.activationId(),
            activation.terminalProfileCode(), command.deviceFingerprintSha256(), command.publicKeySha256(), 1,
            command.appVersion(), command.protocolVersion(), command.schemaVersion(), capabilityHash, skew,
            activation.evidenceLevel(), now));
        port.insertCredential(new TerminalRegistryPort.CredentialWrite(idGenerator.next(), activation.tenantId(), deviceId,
            1, secretProtector.digest("credential:" + deviceId + ":1", rawCredential),
            command.deviceFingerprintSha256(), command.publicKeySha256(), now, now.plusDays(CREDENTIAL_DAYS)));
        port.insertCapability(new TerminalRegistryPort.CapabilityWrite(idGenerator.next(), activation.tenantId(), deviceId,
            1, command.appVersion(), command.protocolVersion(), command.schemaVersion(), capabilityJson,
            capabilityHash, local(command.clientTime()), skew, now));
        if (port.consumeActivation(activation.activationId(), deviceId, activation.recordVersion(), now) != 1) {
            throw new ServiceException("TRM_ACTIVATION_CONFLICT: 激活授权已被消费", 409);
        }
        String resultJson = TerminalHash.canonicalJson(objectMapper, Map.of("deviceId", deviceId, "credentialVersion", 1));
        port.insertCommand(new TerminalRegistryPort.CommandWrite(idGenerator.next(), activation.tenantId(), "ACTIVATE", key,
            requestHash, deviceId, "ACTIVE", resultJson, SyncHash.evidence(resultJson), now));
        appendAudit(new TerminalAuditCommand(activation.tenantId(), deviceId, activation.storeId(),
            "TERMINAL_ACTIVATED", null, "ACTIVE", requestHash, "DEVICE", deviceId, "软件生成密钥激活", now));
        return new ActivatedTerminal(deviceId, activation.tenantId(), activation.orgUnitId(), activation.storeId(),
            deviceId, rawCredential, 1, "ACTIVE", true);
    }

    @Transactional
    public void cancel(String activationId) {
        SyncRules.requireUlid(activationId, "activationId");
        TrustedPrincipal actor = tenantContext.requirePrincipal();
        ActivationRecord activation = port.lockActivationById(activationId);
        if (activation == null || !actor.tenantId().equals(activation.tenantId())) {
            throw new ServiceException("TRM_ACTIVATION_NOT_FOUND: 激活授权不存在或不可见", 404);
        }
        authorizationService.requireStoreAccess(activation.storeId());
        if ("CANCELLED".equals(activation.status())) return;
        if (port.cancelActivation(actor.tenantId(), activationId, actor.userId(), now()) != 1) {
            throw new ServiceException("TRM_ACTIVATION_CONFLICT: 只有未消费授权可取消", 409);
        }
        appendAudit(new TerminalAuditCommand(actor.tenantId(), null, activation.storeId(), "ACTIVATION_CANCELLED",
            activation.status(), "CANCELLED", activation.requestSha256(), "USER", actor.userId().toString(),
            "管理员取消激活", now()));
    }

    @Transactional(readOnly = true)
    public TerminalPage list(Long storeId, int page, int size) {
        String tenantId = tenantContext.requireTenantId();
        if (page < 1 || size < 1 || size > 200) throw new ServiceException("TRM_INPUT_INVALID: 分页参数无效", 400);
        if (storeId != null) {
            authorizationService.requireStoreAccess(storeId);
        } else {
            authorizationService.requireTenantAdministrator();
        }
        int offset = Math.multiplyExact(page - 1, size);
        return new TerminalPage(port.listDevices(tenantId, storeId, offset, size),
            port.countDevices(tenantId, storeId), page, size);
    }

    @Transactional
    public TerminalView changeStatus(String deviceId, ChangeTerminalStatusCommand command) {
        SyncRules.requireUlid(deviceId, "deviceId");
        TrustedPrincipal actor = tenantContext.requirePrincipal();
        String target = TerminalRules.requireStatus(command.targetStatus());
        String reason = TerminalRules.requireReason(command.reason());
        String key = TerminalRules.requireIdempotencyKey(command.idempotencyKey());
        TerminalView device = port.lockDevice(actor.tenantId(), deviceId);
        if (device == null) throw new ServiceException("TRM_DEVICE_NOT_FOUND: 终端不存在或不可见", 404);
        authorizationService.requireStoreAccess(device.storeId());
        String requestHash = TerminalHash.digest(objectMapper, Map.of("device", deviceId, "target", target,
            "reason", reason, "version", command.expectedVersion()));
        StoredCommand previous = port.findCommand(actor.tenantId(), "STATUS", key);
        if (previous != null) {
            requireSameHash(previous.requestSha256(), requestHash);
            return requireDevice(actor.tenantId(), deviceId);
        }
        TerminalRules.requireTransition(device.status(), target);
        if ("BLOCKED".equals(device.status()) && "ACTIVE".equals(target)) {
            authorizationService.requireTenantAdministrator();
        }
        LocalDateTime at = now();
        if (port.changeStatus(new TerminalRegistryPort.StatusChange(actor.tenantId(), deviceId, device.status(), target,
            reason, command.expectedVersion(), at)) != 1) {
            throw new ServiceException("TRM_STATE_CONFLICT: 终端状态或版本已变化", 409);
        }
        if ("REVOKED".equals(target) || "RETIRED".equals(target)) {
            port.invalidateActiveCredential(actor.tenantId(), deviceId, "REVOKED", at);
        }
        String result = TerminalHash.canonicalJson(objectMapper, Map.of("deviceId", deviceId, "status", target));
        port.insertCommand(new TerminalRegistryPort.CommandWrite(idGenerator.next(), actor.tenantId(), "STATUS", key,
            requestHash, deviceId, target, result, SyncHash.evidence(result), at));
        appendAudit(new TerminalAuditCommand(actor.tenantId(), deviceId, device.storeId(), "TERMINAL_STATUS_CHANGED",
            device.status(), target, requestHash, "USER", actor.userId().toString(), reason, at));
        return requireDevice(actor.tenantId(), deviceId);
    }

    /** 管理员轮换设备凭据；旧凭据在同一事务中立即失效。 */
    @Transactional
    public RotatedCredential rotateCredential(String deviceId, String idempotencyKey) {
        SyncRules.requireUlid(deviceId, "deviceId");
        TrustedPrincipal actor = tenantContext.requirePrincipal();
        String key = TerminalRules.requireIdempotencyKey(idempotencyKey);
        TerminalView device = port.lockDevice(actor.tenantId(), deviceId);
        if (device == null || !"ACTIVE".equals(device.status())) {
            throw new ServiceException("TRM_DEVICE_NOT_ACTIVE: 终端不存在、不可见或未激活", 409);
        }
        authorizationService.requireStoreAccess(device.storeId());
        String requestHash = TerminalHash.digest(objectMapper, Map.of("deviceId", deviceId, "action", "ROTATE_CREDENTIAL"));
        StoredCommand previous = port.findCommand(actor.tenantId(), "ROTATE_CREDENTIAL", key);
        if (previous != null) {
            requireSameHash(previous.requestSha256(), requestHash);
            return new RotatedCredential(deviceId, device.credentialVersion(), null, false);
        }
        var current = port.findActiveCredential(actor.tenantId(), deviceId);
        if (current == null) throw new ServiceException("TRM_CREDENTIAL_MISSING: 活跃凭据不存在", 409);
        long nextVersion = current.credentialVersion() + 1;
        LocalDateTime at = now();
        String raw = secretGenerator.next();
        port.invalidateActiveCredential(actor.tenantId(), deviceId, "ROTATED", at);
        port.insertCredential(new TerminalRegistryPort.CredentialWrite(idGenerator.next(), actor.tenantId(), deviceId,
            nextVersion, secretProtector.digest("credential:" + deviceId + ":" + nextVersion, raw),
            current.fingerprintSha256(), current.publicKeySha256(), at, at.plusDays(CREDENTIAL_DAYS)));
        if (port.updateCredentialVersion(new TerminalRegistryPort.CredentialVersionChange(actor.tenantId(), deviceId,
            current.credentialVersion(), nextVersion, at)) != 1) {
            throw new ServiceException("TRM_CREDENTIAL_CONFLICT: 凭据版本已变化", 409);
        }
        String result = TerminalHash.canonicalJson(objectMapper, Map.of("deviceId", deviceId,
            "credentialVersion", nextVersion));
        port.insertCommand(new TerminalRegistryPort.CommandWrite(idGenerator.next(), actor.tenantId(),
            "ROTATE_CREDENTIAL", key, requestHash, deviceId, Long.toString(nextVersion), result,
            SyncHash.evidence(result), at));
        appendAudit(new TerminalAuditCommand(actor.tenantId(), deviceId, device.storeId(), "CREDENTIAL_ROTATED",
            device.status(), device.status(), SyncHash.evidence(deviceId, Long.toString(nextVersion)), "USER",
            actor.userId().toString(), "管理员轮换凭据", at));
        return new RotatedCredential(deviceId, nextVersion, raw, true);
    }

    /** 已认证会话只能为自身追加能力快照，且禁止版本或能力摘要回退。 */
    @Transactional
    public String reportCapability(ReportCapabilityCommand command) {
        SyncRules.requireUlid(command.deviceId(), "deviceId");
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        TerminalView device = port.lockDevice(principal.tenantId(), command.deviceId());
        if (device == null || !principal.userId().equals(device.boundUserId()) || !"ACTIVE".equals(device.status())) {
            throw new ServiceException("TRM_DEVICE_NOT_AUTHORIZED: 终端绑定无效", 401);
        }
        authorizationService.requireStoreAccess(device.storeId());
        TerminalRules.requireVersion(command.appVersion(), "appVersion");
        TerminalRules.requireVersion(command.protocolVersion(), "protocolVersion");
        TerminalRules.requireVersion(command.schemaVersion(), "schemaVersion");
        if (TerminalRules.compareVersion(command.appVersion(), device.appVersion()) < 0
            || TerminalRules.compareVersion(command.schemaVersion(), device.schemaVersion()) < 0
            || TerminalRules.compareVersion(command.protocolVersion(), device.minProtocolVersion()) < 0
            || TerminalRules.compareVersion(command.protocolVersion(), device.maxProtocolVersion()) > 0) {
            throw new ServiceException("TRM_VERSION_DOWNGRADE: 版本或兼容窗口校验失败", 409);
        }
        String key = TerminalRules.requireIdempotencyKey(command.idempotencyKey());
        String json = TerminalHash.canonicalJson(objectMapper, command.capability());
        String hash = SyncHash.evidence(json);
        long skew = TerminalRules.clockSkewSeconds(command.clientTime(), clock.instant());
        TerminalRules.requireClockSkew(skew);
        String requestHash = TerminalHash.digest(objectMapper, Map.of("device", command.deviceId(), "app", command.appVersion(),
            "protocol", command.protocolVersion(), "schema", command.schemaVersion(), "capability", hash,
            "clientTime", command.clientTime().toString()));
        StoredCommand previous = port.findCommand(principal.tenantId(), "CAPABILITY", key);
        if (previous != null) {
            requireSameHash(previous.requestSha256(), requestHash);
            return previous.resultCode();
        }
        LocalDateTime at = now();
        if (port.findCapabilityDigest(principal.tenantId(), command.deviceId(), hash) == null) {
            port.insertCapability(new TerminalRegistryPort.CapabilityWrite(idGenerator.next(), principal.tenantId(),
                command.deviceId(), port.nextCapabilitySequence(principal.tenantId(), command.deviceId()),
                command.appVersion(), command.protocolVersion(), command.schemaVersion(), json, hash,
                local(command.clientTime()), skew, at));
        }
        if (port.updateDeviceCapability(new TerminalRegistryPort.DeviceCapabilityChange(principal.tenantId(),
            command.deviceId(), command.appVersion(), command.schemaVersion(), hash,
            skew, at)) != 1) {
            throw new ServiceException("TRM_DEVICE_BLOCKED: 终端状态已变化", 423);
        }
        String result = TerminalHash.canonicalJson(objectMapper, Map.of("capabilitySha256", hash));
        port.insertCommand(new TerminalRegistryPort.CommandWrite(idGenerator.next(), principal.tenantId(), "CAPABILITY",
            key, requestHash, command.deviceId(), hash, result, SyncHash.evidence(result), at));
        appendAudit(new TerminalAuditCommand(principal.tenantId(), command.deviceId(), device.storeId(),
            "CAPABILITY_REPORTED", device.status(), device.status(), requestHash, "DEVICE", command.deviceId(),
            "终端能力上报", at));
        return hash;
    }

    private StoreView requireStore(Long storeId, Long expectedOrgId) {
        requirePositive(storeId, "storeId");
        requirePositive(expectedOrgId, "orgUnitId");
        return storeService.list().stream().filter(store -> store.storeId().equals(storeId)
                && store.orgUnitId().equals(expectedOrgId) && "ACTIVE".equals(store.status()))
            .findFirst().orElseThrow(() -> new ServiceException("TRM_STORE_NOT_AVAILABLE: 门店不存在、越权、组织不匹配或未启用", 404));
    }

    private TerminalView requireDevice(String tenantId, String deviceId) {
        TerminalView device = port.findDevice(tenantId, deviceId);
        if (device == null) throw new ServiceException("TRM_DEVICE_NOT_FOUND: 终端不存在或不可见", 404);
        return device;
    }

    private ActivatedTerminal activatedView(String tenantId, TerminalView device, String rawCredential, boolean shown) {
        return new ActivatedTerminal(device.deviceId(), tenantId, device.orgUnitId(), device.storeId(), device.terminalId(),
            rawCredential, device.credentialVersion(), device.status(), shown);
    }

    private void appendAudit(TerminalAuditCommand command) {
        String correlation = MDC.get("correlationId");
        if (correlation == null || correlation.isBlank()) correlation = idGenerator.next();
        port.insertAudit(new TerminalRegistryPort.AuditWrite(idGenerator.next(), command.tenantId(), command.deviceId(),
            command.storeId(), command.action(), command.beforeStatus(), command.afterStatus(), command.evidenceSha256(),
            command.actorType(), command.actorId(), command.reason(), correlation, command.occurredAt()));
    }

    private void requireSameHash(String expected, String actual) {
        if (!expected.equals(actual)) throw new ServiceException("TRM_IDEMPOTENCY_CONFLICT: 同键异内容", 409);
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value <= 0) throw new ServiceException("TRM_INPUT_INVALID: " + field + " 必须为正数", 400);
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private LocalDateTime local(java.time.Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
