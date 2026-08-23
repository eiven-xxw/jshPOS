package com.jingshanghui.pos.saas.application.service;

import com.jingshanghui.pos.saas.application.model.SaasModels.CommandRecord;
import com.jingshanghui.pos.saas.application.model.SaasModels.SubscriptionAccessRecord;
import com.jingshanghui.pos.saas.application.model.SaasModels.TenantEntitlementRecord;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort.*;
import com.jingshanghui.pos.saas.application.port.SaasSubscriptionControlPort;
import com.jingshanghui.pos.saas.domain.SaasIdGenerator;
import com.jingshanghui.pos.saas.domain.SaasRules;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** SaaS Owner 对 Subscription 暴露的最小正式端口实现。 */
@Service
@RequiredArgsConstructor
public class SaasSubscriptionControlService implements SaasSubscriptionControlPort {
    private static final Set<String> MODES = Set.of("NORMAL", "GRACE", "RECOVERY_ONLY", "TERMINATED_RECOVERY");
    private final SaasPersistencePort persistence;
    private final SaasIdGenerator ids;

    @Override
    @Transactional(readOnly = true)
    public TenantPlanSnapshot requireTenantPlan(String tenantId) {
        String trustedTarget = SaasRules.required(tenantId, "tenantId");
        TenantEntitlementRecord record = persistence.findTenantEntitlement(trustedTarget);
        if (record == null) throw new ServiceException("SUB-SAA-001: 目标租户未完成 SaaS 开户", 409);
        if (!trustedTarget.equals(record.tenantId()))
            throw new ServiceException("SUB-SAA-007: SaaS 租户记录与可信目标不一致", 409);
        return new TenantPlanSnapshot(record.tenantId(), record.planId(), record.versionId(), record.lifecycleState());
    }

    @Override
    @Transactional
    public void applySubscriptionAccess(ApplyAccessCommand command) {
        requireTenantPlan(command.tenantId());
        String mode = SaasRules.code(command.accessMode(), "accessMode");
        if (!MODES.contains(mode)) throw new ServiceException("SUB-SAA-002: 未知订阅访问模式", 409);
        String key = SaasRules.key(command.idempotencyKey());
        String sourceHash = SaasRules.hash(command.sourceSha256());
        CommandRecord replay = persistence.findCommand(command.tenantId(), "SUBSCRIPTION_ACCESS", key);
        if (replay != null) {
            if (!replay.requestSha256().equals(sourceHash))
                throw new ServiceException("SUB-SAA-003: 同幂等键内容不一致", 409);
            return;
        }
        SubscriptionAccessRecord current = persistence.findSubscriptionAccess(command.tenantId());
        if (current == null) {
            persistence.insertSubscriptionAccess(new SubscriptionAccessWrite(command.tenantId(), command.subscriptionId(),
                mode, command.sourceVersion(), sourceHash, command.occurredAt()));
        } else {
            if (!current.subscriptionId().equals(command.subscriptionId()))
                throw new ServiceException("SUB-SAA-004: 租户已绑定其他订阅", 409);
            if (current.sourceVersion() > command.sourceVersion()) return;
            if (current.sourceVersion().equals(command.sourceVersion())) {
                if (!current.sourceSha256().equals(sourceHash) || !current.accessMode().equals(mode))
                    throw new ServiceException("SUB-SAA-008: 同一订阅来源版本内容不一致", 409);
                return;
            }
            if (persistence.changeSubscriptionAccess(new SubscriptionAccessChange(command.tenantId(),
                command.subscriptionId(), mode, command.sourceVersion(), sourceHash,
                current.recordVersion(), command.occurredAt())) != 1)
                throw new ServiceException("SUB-SAA-005: 订阅访问并发冲突", 409);
        }
        persistence.appendSubscriptionAccessEvent(new SubscriptionAccessEventWrite(ids.next(), command.tenantId(),
            command.subscriptionId(), current == null ? null : current.accessMode(), mode, command.sourceVersion(),
            sourceHash, command.correlationId(), command.occurredAt()));
        persistence.insertCommand(new CommandWrite(ids.next(), command.tenantId(), "SUBSCRIPTION_ACCESS", key,
            sourceHash, command.subscriptionId(), mode, command.occurredAt()));
    }
}
