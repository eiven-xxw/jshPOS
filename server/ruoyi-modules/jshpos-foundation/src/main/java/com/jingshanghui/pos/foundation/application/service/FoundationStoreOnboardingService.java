package com.jingshanghui.pos.foundation.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.ConfigBindingView;
import com.jingshanghui.pos.foundation.application.port.StoreOnboardingPort;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigBindingEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateVersionEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StaffScopeEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigBindingMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateVersionMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StaffScopeMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Foundation Owner 对门店开通提供白名单快照、模板绑定和门店激活。 */
@Service
@RequiredArgsConstructor
public class FoundationStoreOnboardingService implements StoreOnboardingPort {
    private static final Set<String> INDUSTRIES = Set.of("CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET");
    private static final Set<String> WHITELIST = Set.of("business.time", "business.day", "ui.layout",
        "device.expectation", "industry.template", "catalog.scope", "pricing.scope",
        "permission.template", "receipt.template", "approval.policy");
    private static final Set<String> FORBIDDEN = Set.of("secret", "token", "password", "credential",
        "privatekey", "member.identity", "payment.raw", "order", "inventory.balance", "cost",
        "points", "audit", "inbox", "outbox");

    private final StoreMapper storeMapper;
    private final ConfigTemplateMapper templateMapper;
    private final ConfigTemplateVersionMapper versionMapper;
    private final ConfigBindingMapper bindingMapper;
    private final StaffScopeMapper staffScopeMapper;
    private final ConfigGovernanceService configService;
    private final ScopeAuthorizationService authorization;
    private final DomainAuditService audit;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public FoundationSnapshot capture(CaptureCommand command) {
        authorization.requireTenantAdministrator();
        requirePositive(command.targetStoreId(), "targetStoreId");
        requirePositive(command.templateId(), "templateId");
        requirePositive(command.templateVersionId(), "templateVersionId");
        if (command.sourceStoreId() != null && command.sourceStoreId().equals(command.targetStoreId())) {
            throw new ServiceException("ONB-FND-001: 来源门店不能等于目标门店", 409);
        }
        StoreEntity target = requireStore(command.targetStoreId());
        authorization.requireStoreAccess(target.getStoreId());
        if (!"PREPARING".equals(target.getStatus())) {
            throw new ServiceException("ONB-FND-002: 目标门店必须处于 PREPARING", 409);
        }
        StoreEntity source = null;
        if (command.sourceStoreId() != null) {
            source = requireStore(command.sourceStoreId());
            authorization.requireStoreAccess(source.getStoreId());
            if (!"ACTIVE".equals(source.getStatus())) {
                throw new ServiceException("ONB-FND-003: 来源门店必须处于 ACTIVE", 409);
            }
        }
        ConfigTemplateEntity template = templateMapper.selectById(command.templateId());
        ConfigTemplateVersionEntity version = versionMapper.selectById(command.templateVersionId());
        if (template == null || version == null || !template.getTemplateId().equals(version.getTemplateId())
            || !"ACTIVE".equals(template.getStatus()) || !"PUBLISHED".equals(version.getState())
            || !INDUSTRIES.contains(template.getIndustry())) {
            throw new ServiceException("ONB-FND-004: 行业模板或已发布版本不可用", 409);
        }
        if (source != null) {
            ConfigBindingEntity binding = bindingMapper.selectOne(new LambdaQueryWrapper<ConfigBindingEntity>()
                .eq(ConfigBindingEntity::getTemplateId, command.templateId())
                .eq(ConfigBindingEntity::getTargetType, "STORE")
                .eq(ConfigBindingEntity::getTargetId, source.getStoreId()), false);
            if (binding == null || !command.templateVersionId().equals(binding.getCurrentVersionId())) {
                throw new ServiceException("ONB-FND-005: 来源门店未绑定所选模板版本", 409);
            }
        }
        Map<String, Object> items = whitelist(version.getContentJson());
        return new FoundationSnapshot(source == null ? null : source.getStoreId(),
            source == null ? null : source.getVersion(), target.getStoreId(), target.getVersion(),
            template.getTemplateId(), version.getConfigVersionId(), version.getVersionNo(),
            version.getContentSha256(), template.getIndustry(), items);
    }

    @Override
    @Transactional(readOnly = true)
    public FoundationReadiness readiness(Long targetStoreId) {
        authorization.requireTenantAdministrator();
        authorization.requireStoreAccess(targetStoreId);
        StoreEntity store = requireStore(targetStoreId);
        long activeScopes = staffScopeMapper.selectCount(new LambdaQueryWrapper<StaffScopeEntity>()
            .eq(StaffScopeEntity::getStatus, "ACTIVE")
            .and(scope -> scope.eq(StaffScopeEntity::getScopeType, "TENANT")
                .or().eq(StaffScopeEntity::getStoreId, targetStoreId)
                .or().eq(StaffScopeEntity::getOrgUnitId, store.getOrgUnitId())));
        String factHash = CanonicalJson.from(Map.of("targetStoreId", targetStoreId,
            "activeStaffScopeCount", activeScopes)).sha256();
        return new FoundationReadiness(targetStoreId, Math.toIntExact(activeScopes), factHash);
    }

    @Override
    @Transactional
    public AppliedBinding apply(ApplyCommand command) {
        FoundationSnapshot snapshot = capture(new CaptureCommand(null, command.targetStoreId(),
            command.templateId(), command.templateVersionId()));
        if (!snapshot.targetStoreVersion().equals(command.expectedTargetVersion())) {
            throw new ServiceException("ONB-FND-006: 目标门店版本已漂移", 409);
        }
        CanonicalJson.Result canonical = CanonicalJson.from(snapshot.configItems());
        if (!canonical.sha256().equals(command.expectedSnapshotSha256())) {
            throw new ServiceException("ONB-FND-007: 冻结配置摘要已漂移", 409);
        }
        ConfigBindingView binding = configService.activate(new ConfigGovernanceService.ActivateConfig(
            command.templateId(), command.templateVersionId(), "STORE", command.targetStoreId()));
        String result = CanonicalJson.from(Map.of("bindingId", binding.bindingId(), "targetStoreId",
            command.targetStoreId(), "versionId", binding.currentVersionId())).sha256();
        return new AppliedBinding(binding.bindingId(), command.targetStoreId(), binding.currentVersionId(),
            binding.version(), result);
    }

    @Override
    @Transactional
    public OpenedStore open(OpenCommand command) {
        authorization.requireTenantAdministrator();
        authorization.requireStoreAccess(command.targetStoreId());
        StoreEntity before = requireStore(command.targetStoreId());
        if ("ACTIVE".equals(before.getStatus())) {
            return new OpenedStore(before.getStoreId(), before.getStatus(), before.getVersion());
        }
        if (!"PREPARING".equals(before.getStatus()) || !before.getVersion().equals(command.expectedStoreVersion())) {
            throw new ServiceException("ONB-FND-008: 门店状态或版本不允许开店", 409);
        }
        int updated = storeMapper.update(null, new LambdaUpdateWrapper<StoreEntity>()
            .eq(StoreEntity::getStoreId, command.targetStoreId())
            .eq(StoreEntity::getStatus, "PREPARING")
            .eq(StoreEntity::getVersion, command.expectedStoreVersion())
            .set(StoreEntity::getStatus, "ACTIVE")
            .set(StoreEntity::getVersion, command.expectedStoreVersion() + 1));
        if (updated != 1) throw new ServiceException("ONB-FND-009: 门店开店并发冲突", 409);
        OpenedStore result = new OpenedStore(before.getStoreId(), "ACTIVE", command.expectedStoreVersion() + 1);
        audit.append("STORE_OPENED_BY_ONBOARDING", "STORE", before.getStoreId(),
            Map.of("status", before.getStatus(), "version", before.getVersion()), result,
            Map.of("reason", safeReason(command.reason())));
        return result;
    }

    private StoreEntity requireStore(Long storeId) {
        StoreEntity value = storeMapper.selectById(storeId);
        if (value == null) throw new ServiceException("ONB-FND-010: 门店不存在或不可见", 404);
        return value;
    }

    private Map<String, Object> whitelist(String json) {
        try {
            Map<String, Object> source = objectMapper.readValue(json, new TypeReference<>() { });
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().strip();
                String normalized = key.toLowerCase(Locale.ROOT);
                if (!WHITELIST.contains(key)) {
                    throw new ServiceException("ONB-FND-011: 配置键不在复制白名单", 409);
                }
                String canonical = objectMapper.writeValueAsString(entry.getValue()).toLowerCase(Locale.ROOT);
                if (FORBIDDEN.stream().anyMatch(token -> normalized.contains(token) || canonical.contains(token))) {
                    throw new ServiceException("ONB-FND-012: 配置包含禁止复制的敏感或历史事实", 409);
                }
                result.put(key, entry.getValue());
            }
            if (result.isEmpty()) throw new ServiceException("ONB-FND-013: 模板没有可复制白名单配置", 409);
            // 模板可显式声明可选配置为空；保留 null 参与规范摘要，但仍禁止调用方修改快照。
            return Collections.unmodifiableMap(new LinkedHashMap<>(result));
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("ONB-FND-014: 模板内容无法安全解析", 409);
        }
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value <= 0) throw new ServiceException("ONB-FND-015: " + name + " 非法", 400);
    }

    private static String safeReason(String reason) {
        String value = reason == null ? "" : reason.strip();
        if (value.length() < 2 || value.length() > 200 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new ServiceException("ONB-FND-016: 开店原因非法", 400);
        }
        return value;
    }
}
