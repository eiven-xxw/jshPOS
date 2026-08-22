package com.jingshanghui.pos.foundation.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigBindingEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.ConfigTemplateVersionEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigBindingMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.ConfigTemplateVersionMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Foundation Owner 对门店当前行业模板提供失败关闭的可信读取。 */
@Service
@RequiredArgsConstructor
public class StoreIndustryReadService implements StoreIndustryReadPort {
    private final ConfigBindingMapper bindingMapper;
    private final ConfigTemplateMapper templateMapper;
    private final ConfigTemplateVersionMapper versionMapper;
    private final StoreMapper storeMapper;
    private final ScopeAuthorizationService authorization;
    private final TrustedTenantContext tenantContext;

    @Override
    @Transactional(readOnly = true)
    public IndustryBinding requireCurrentIndustry(Long storeId) {
        if (storeId == null || storeId <= 0) {
            throw new ServiceException("FND-IND-001: 门店标识非法", 400);
        }
        authorization.requireStoreAccess(storeId);
        ConfigBindingEntity binding = bindingMapper.selectOne(new LambdaQueryWrapper<ConfigBindingEntity>()
            .eq(ConfigBindingEntity::getTargetType, "STORE")
            .eq(ConfigBindingEntity::getTargetId, storeId)
            .orderByDesc(ConfigBindingEntity::getActivatedAt)
            .last("LIMIT 1"), false);
        if (binding == null || binding.getCurrentVersionId() == null) {
            throw new ServiceException("FND-IND-002: 门店尚未绑定已发布行业模板", 409);
        }
        ConfigTemplateVersionEntity version = versionMapper.selectById(binding.getCurrentVersionId());
        ConfigTemplateEntity template = templateMapper.selectById(binding.getTemplateId());
        var store = storeMapper.selectById(storeId);
        String tenantId = tenantContext.requireTenantId();
        if (version == null || template == null || !"PUBLISHED".equals(version.getState())
            || !"ACTIVE".equals(template.getStatus())
            || !template.getTemplateId().equals(version.getTemplateId()) || store == null
            || !tenantId.equals(binding.getTenantId()) || !tenantId.equals(version.getTenantId())
            || !tenantId.equals(template.getTenantId()) || !tenantId.equals(store.getTenantId())
            || store.getZoneId() == null || store.getBusinessDayStart() == null) {
            throw new ServiceException("FND-IND-003: 门店行业模板身份或发布状态无效", 409);
        }
        return new IndustryBinding(storeId, template.getTemplateId(), version.getConfigVersionId(),
            version.getVersionNo(), template.getIndustry(), version.getContentSha256(),
            store.getZoneId(), store.getBusinessDayStart());
    }
}
