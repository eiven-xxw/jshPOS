package com.jingshanghui.pos.foundation.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.ConfigBindingView;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.ConfigTemplateView;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.ConfigVersionView;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryTransitionGuardPort;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryTransitionGuardPort.IndustryTransition;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.foundation.domain.FoundationRules;
import com.jingshanghui.pos.foundation.infrastructure.observability.FoundationMetrics;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigBindingEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateVersionEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigBindingMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateVersionMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConfigGovernanceService {

    private final ConfigTemplateMapper templateMapper;
    private final ConfigTemplateVersionMapper versionMapper;
    private final ConfigBindingMapper bindingMapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final DomainAuditService auditService;
    private final FoundationMetrics metrics;
    private final Clock clock;
    private final ObjectProvider<StoreIndustryTransitionGuardPort> industryTransitionGuards;

    @Transactional(readOnly = true)
    public List<ConfigTemplateView> listTemplates() {
        tenantContext.requirePrincipal();
        return templateMapper.selectList(new LambdaQueryWrapper<ConfigTemplateEntity>()
                .orderByAsc(ConfigTemplateEntity::getTemplateCode))
            .stream().map(this::toTemplateView).toList();
    }

    @Transactional
    public ConfigTemplateView createTemplate(CreateTemplate command) {
        authorizationService.requireTenantAdministrator();
        ConfigTemplateEntity entity = new ConfigTemplateEntity();
        entity.setTenantId(tenantContext.requireTenantId());
        entity.setTemplateCode(FoundationRules.requireCode(command.code()));
        entity.setTemplateName(FoundationRules.requireName(command.name()));
        entity.setIndustry(FoundationRules.requireEnum(command.industry(), FoundationRules.INDUSTRIES, "FND-CFG-005"));
        entity.setStatus("ACTIVE");
        entity.setVersion(0);
        templateMapper.insert(entity);
        ConfigTemplateView after = toTemplateView(entity);
        auditService.append("CONFIG_TEMPLATE_CREATED", "CONFIG_TEMPLATE", entity.getTemplateId(), null, after,
            Map.of("code", entity.getTemplateCode(), "industry", entity.getIndustry()));
        metrics.increment("config.template.create", "success");
        return after;
    }

    @Transactional
    public ConfigVersionView createVersion(Long templateId, CreateVersion command) {
        authorizationService.requireTenantAdministrator();
        ConfigTemplateEntity template = requireTemplate(templateId);
        if (!"ACTIVE".equals(template.getStatus())) {
            throw new ServiceException("FND-CFG-006: 配置模板已停用", 409);
        }
        CanonicalJson.Result canonical = CanonicalJson.from(command.content());
        String tenantId = tenantContext.requireTenantId();
        ConfigTemplateVersionEntity latest = versionMapper.selectLatestForUpdate(tenantId, templateId);
        ConfigTemplateVersionEntity entity = new ConfigTemplateVersionEntity();
        entity.setTenantId(tenantId);
        entity.setTemplateId(templateId);
        entity.setVersionNo(latest == null ? 1 : latest.getVersionNo() + 1);
        entity.setSchemaVersion(FoundationRules.requireSchemaVersion(command.schemaVersion()));
        entity.setState("DRAFT");
        entity.setContentJson(canonical.json());
        entity.setContentSha256(canonical.sha256());
        versionMapper.insert(entity);
        ConfigVersionView after = toVersionView(entity);
        auditService.append("CONFIG_VERSION_CREATED", "CONFIG_VERSION", entity.getConfigVersionId(), null, after,
            Map.of("templateId", templateId, "versionNo", entity.getVersionNo(), "sha256", entity.getContentSha256()));
        metrics.increment("config.version.create", "success");
        return after;
    }

    @Transactional
    public ConfigVersionView publish(Long configVersionId) {
        authorizationService.requireTenantAdministrator();
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ConfigTemplateVersionEntity entity = requireVersion(configVersionId);
        if (!"DRAFT".equals(entity.getState())) {
            throw new ServiceException("FND-CFG-007: 只有 DRAFT 可以发布", 409);
        }
        ConfigVersionView before = toVersionView(entity);
        LocalDateTime publishedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int updated = versionMapper.update(null, new LambdaUpdateWrapper<ConfigTemplateVersionEntity>()
            .eq(ConfigTemplateVersionEntity::getConfigVersionId, configVersionId)
            .eq(ConfigTemplateVersionEntity::getState, "DRAFT")
            .set(ConfigTemplateVersionEntity::getState, "PUBLISHED")
            .set(ConfigTemplateVersionEntity::getPublishedBy, principal.userId())
            .set(ConfigTemplateVersionEntity::getPublishedAt, publishedAt));
        if (updated != 1) {
            throw new ServiceException("FND-CFG-008: 配置版本发布冲突", 409);
        }
        entity.setState("PUBLISHED");
        entity.setPublishedBy(principal.userId());
        entity.setPublishedAt(publishedAt);
        ConfigVersionView after = toVersionView(entity);
        auditService.append("CONFIG_VERSION_PUBLISHED", "CONFIG_VERSION", configVersionId, before, after,
            Map.of("sha256", entity.getContentSha256()));
        metrics.increment("config.version.publish", "success");
        return after;
    }

    @Transactional
    public ConfigBindingView activate(ActivateConfig command) {
        tenantContext.requirePrincipal();
        String targetType = FoundationRules.requireEnum(command.targetType(), FoundationRules.TARGET_TYPES, "FND-CFG-009");
        validateTarget(targetType, command.targetId());
        ConfigTemplateVersionEntity version = requireVersion(command.configVersionId());
        if (!command.templateId().equals(version.getTemplateId()) || !"PUBLISHED".equals(version.getState())) {
            throw new ServiceException("FND-CFG-010: 只能激活本模板已发布版本", 409);
        }
        if ("STORE".equals(targetType)) {
            requireSafeIndustryTransition(command.targetId(), command.templateId());
        }
        ConfigBindingEntity binding = findBinding(command.templateId(), targetType, command.targetId());
        ConfigBindingView before = binding == null ? null : toBindingView(binding);
        if (binding == null) {
            binding = new ConfigBindingEntity();
            binding.setTenantId(tenantContext.requireTenantId());
            binding.setTemplateId(command.templateId());
            binding.setTargetType(targetType);
            binding.setTargetId(command.targetId());
            binding.setCurrentVersionId(command.configVersionId());
            binding.setPreviousVersionId(null);
            binding.setActivatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            binding.setVersion(0);
            bindingMapper.insert(binding);
        } else {
            if (!binding.getCurrentVersionId().equals(command.configVersionId())) {
                binding.setPreviousVersionId(binding.getCurrentVersionId());
                binding.setCurrentVersionId(command.configVersionId());
                binding.setActivatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                if (bindingMapper.updateById(binding) != 1) {
                    throw new ServiceException("FND-CFG-011: 配置激活版本冲突", 409);
                }
            }
        }
        ConfigBindingView after = toBindingView(binding);
        auditService.append("CONFIG_BINDING_ACTIVATED", "CONFIG_BINDING", binding.getBindingId(), before, after,
            Map.of("targetType", targetType, "currentVersionId", binding.getCurrentVersionId()));
        metrics.increment("config.binding.activate", "success");
        return after;
    }

    @Transactional
    public ConfigBindingView rollback(Long bindingId) {
        tenantContext.requirePrincipal();
        ConfigBindingEntity binding = bindingMapper.selectById(bindingId);
        if (binding == null) {
            throw new ServiceException("FND-CFG-012: 配置绑定不存在或不可见", 404);
        }
        validateTarget(binding.getTargetType(), binding.getTargetId());
        if (binding.getPreviousVersionId() == null) {
            throw new ServiceException("FND-CFG-013: 配置绑定没有可回退版本", 409);
        }
        ConfigTemplateVersionEntity previous = requireVersion(binding.getPreviousVersionId());
        if (!"PUBLISHED".equals(previous.getState()) || !binding.getTemplateId().equals(previous.getTemplateId())) {
            throw new ServiceException("FND-CFG-014: 前一版本不可回退", 409);
        }
        ConfigBindingView before = toBindingView(binding);
        Long oldCurrent = binding.getCurrentVersionId();
        binding.setCurrentVersionId(binding.getPreviousVersionId());
        binding.setPreviousVersionId(oldCurrent);
        binding.setActivatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        if (bindingMapper.updateById(binding) != 1) {
            throw new ServiceException("FND-CFG-011: 配置回退版本冲突", 409);
        }
        ConfigBindingView after = toBindingView(binding);
        auditService.append("CONFIG_BINDING_ROLLED_BACK", "CONFIG_BINDING", bindingId, before, after,
            Map.of("currentVersionId", binding.getCurrentVersionId(), "previousVersionId", binding.getPreviousVersionId()));
        metrics.increment("config.binding.rollback", "success");
        return after;
    }

    private void validateTarget(String targetType, Long targetId) {
        if ("TENANT".equals(targetType)) {
            if (targetId != null) {
                throw new ServiceException("FND-CFG-015: TENANT 绑定不能携带 targetId", 400);
            }
            authorizationService.requireTenantAdministrator();
        } else {
            if (targetId == null) {
                throw new ServiceException("FND-CFG-016: STORE 绑定必须携带 targetId", 400);
            }
            authorizationService.requireStoreAccess(targetId);
        }
    }

    private ConfigTemplateEntity requireTemplate(Long templateId) {
        ConfigTemplateEntity entity = templateMapper.selectById(templateId);
        if (entity == null) {
            throw new ServiceException("FND-CFG-017: 配置模板不存在或不可见", 404);
        }
        return entity;
    }

    private ConfigTemplateVersionEntity requireVersion(Long versionId) {
        ConfigTemplateVersionEntity entity = versionMapper.selectById(versionId);
        if (entity == null) {
            throw new ServiceException("FND-CFG-018: 配置版本不存在或不可见", 404);
        }
        return entity;
    }

    private ConfigBindingEntity findBinding(Long templateId, String targetType, Long targetId) {
        LambdaQueryWrapper<ConfigBindingEntity> query = new LambdaQueryWrapper<ConfigBindingEntity>()
            .eq(ConfigBindingEntity::getTemplateId, templateId)
            .eq(ConfigBindingEntity::getTargetType, targetType);
        if (targetId == null) {
            query.isNull(ConfigBindingEntity::getTargetId);
        } else {
            query.eq(ConfigBindingEntity::getTargetId, targetId);
        }
        return bindingMapper.selectOne(query, false);
    }

    private void requireSafeIndustryTransition(Long storeId, Long targetTemplateId) {
        ConfigBindingEntity current = bindingMapper.selectOne(new LambdaQueryWrapper<ConfigBindingEntity>()
            .eq(ConfigBindingEntity::getTargetType, "STORE")
            .eq(ConfigBindingEntity::getTargetId, storeId)
            .orderByDesc(ConfigBindingEntity::getActivatedAt)
            .last("LIMIT 1"), false);
        if (current == null || current.getTemplateId().equals(targetTemplateId)) {
            return;
        }
        ConfigTemplateEntity from = requireTemplate(current.getTemplateId());
        ConfigTemplateEntity to = requireTemplate(targetTemplateId);
        IndustryTransition transition = new IndustryTransition(storeId, from.getTemplateId(), from.getIndustry(),
            to.getTemplateId(), to.getIndustry());
        industryTransitionGuards.orderedStream().forEach(guard -> guard.requireCanActivate(transition));
    }

    private ConfigTemplateView toTemplateView(ConfigTemplateEntity entity) {
        return new ConfigTemplateView(entity.getTemplateId(), entity.getTemplateCode(), entity.getTemplateName(),
            entity.getIndustry(), entity.getStatus(), entity.getVersion());
    }

    private ConfigVersionView toVersionView(ConfigTemplateVersionEntity entity) {
        return new ConfigVersionView(entity.getConfigVersionId(), entity.getTemplateId(), entity.getVersionNo(),
            entity.getSchemaVersion(), entity.getState(), entity.getContentSha256());
    }

    private ConfigBindingView toBindingView(ConfigBindingEntity entity) {
        return new ConfigBindingView(entity.getBindingId(), entity.getTemplateId(), entity.getTargetType(),
            entity.getTargetId(), entity.getCurrentVersionId(), entity.getPreviousVersionId(), entity.getVersion());
    }

    public record CreateTemplate(String code, String name, String industry) {
    }

    public record CreateVersion(String schemaVersion, Map<String, Object> content) {
    }

    public record ActivateConfig(Long templateId, Long configVersionId, String targetType, Long targetId) {
    }
}
