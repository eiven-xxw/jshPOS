package com.jingshanghui.pos.foundation.application.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.PublishedConfigReadPort;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigBindingEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateVersionEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigBindingMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateVersionMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Foundation Owner 对外提供的已发布配置只读实现，阻止其他 Owner 直接查询配置私表。 */
@Service
@RequiredArgsConstructor
public class PublishedConfigReadService implements PublishedConfigReadPort {

    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final ConfigTemplateMapper templateMapper;
    private final ConfigBindingMapper bindingMapper;
    private final ConfigTemplateVersionMapper versionMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<PublishedConfig> find(String templateCode, Long storeId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        if (templateCode == null || !templateCode.matches("^[A-Z][A-Z0-9_]{2,63}$") || storeId == null) {
            throw new ServiceException("FND-CFG-020: 配置读取条件无效", 400);
        }
        authorization.requireStoreAccess(storeId);
        ConfigTemplateEntity template = templateMapper.selectOne(Wrappers.<ConfigTemplateEntity>lambdaQuery()
            .eq(ConfigTemplateEntity::getTemplateCode, templateCode)
            .eq(ConfigTemplateEntity::getStatus, "ACTIVE")
            .last("LIMIT 1"));
        if (template == null) {
            return Optional.empty();
        }
        List<ConfigBindingEntity> candidates = bindingMapper.selectList(Wrappers.<ConfigBindingEntity>lambdaQuery()
            .eq(ConfigBindingEntity::getTemplateId, template.getTemplateId())
            .and(query -> query
                .and(store -> store.eq(ConfigBindingEntity::getTargetType, "STORE")
                    .eq(ConfigBindingEntity::getTargetId, storeId))
                .or(tenant -> tenant.eq(ConfigBindingEntity::getTargetType, "TENANT")
                    .isNull(ConfigBindingEntity::getTargetId))));
        ConfigBindingEntity binding = candidates.stream()
            .sorted(Comparator
                .comparingInt((ConfigBindingEntity value) -> "STORE".equals(value.getTargetType()) ? 0 : 1)
                .thenComparing(ConfigBindingEntity::getBindingId, Comparator.reverseOrder()))
            .findFirst().orElse(null);
        if (binding == null) {
            return Optional.empty();
        }
        ConfigTemplateVersionEntity version = versionMapper.selectById(binding.getCurrentVersionId());
        if (version == null || !template.getTemplateId().equals(version.getTemplateId())
            || !"PUBLISHED".equals(version.getState()) || version.getContentJson() == null
            || version.getContentSha256() == null) {
            throw new ServiceException("FND-CFG-021: 配置绑定未指向完整的已发布版本", 409);
        }
        return Optional.of(new PublishedConfig(principal.tenantId(), template.getTemplateId(),
            version.getConfigVersionId(), version.getVersionNo(), version.getContentJson(),
            version.getContentSha256()));
    }
}
