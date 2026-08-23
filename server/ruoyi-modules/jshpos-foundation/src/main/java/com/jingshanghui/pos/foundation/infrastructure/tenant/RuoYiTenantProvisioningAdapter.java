package com.jingshanghui.pos.foundation.infrastructure.tenant;

import com.jingshanghui.pos.foundation.application.port.TenantProvisioningPort;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.bo.SysTenantBo;
import org.dromara.system.domain.vo.SysTenantVo;
import org.dromara.system.service.ISysTenantService;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** RuoYi 技术租户适配器；商业规则不得进入本类。 */
@Component
@RequiredArgsConstructor
public class RuoYiTenantProvisioningAdapter implements TenantProvisioningPort {
    private final ISysTenantService tenantService;

    @Override
    public ProvisionedTenant provision(ProvisionTenant command) {
        char[] secret = command.bootstrapPassword();
        try {
            SysTenantBo bo = new SysTenantBo();
            bo.setCompanyName(command.companyName());
            bo.setContactUserName(command.contactName());
            bo.setContactPhone(command.contactPhone());
            bo.setUsername(command.bootstrapUsername());
            bo.setPassword(new String(secret));
            bo.setPackageId(command.platformPackageId());
            bo.setAccountCount(command.accountLimit());
            bo.setStatus("1");
            if (!Boolean.TRUE.equals(tenantService.insertByBo(bo)) || bo.getId() == null) {
                throw new ServiceException("SAA-FND-001: 技术租户创建失败", 409);
            }
            SysTenantVo created = tenantService.queryById(bo.getId());
            if (created == null || created.getTenantId() == null || created.getTenantId().isBlank()) {
                throw new ServiceException("SAA-FND-002: 技术租户标识未返回", 409);
            }
            return new ProvisionedTenant(created.getId(), created.getTenantId());
        } finally {
            if (secret != null) Arrays.fill(secret, '\0');
        }
    }

    @Override
    public void changeStatus(String tenantId, TechnicalTenantStatus status) {
        SysTenantVo current = tenantService.queryByTenantId(tenantId);
        if (current == null) throw new ServiceException("SAA-FND-003: 技术租户不存在", 404);
        SysTenantBo bo = new SysTenantBo();
        bo.setId(current.getId());
        bo.setTenantId(current.getTenantId());
        bo.setStatus(status == TechnicalTenantStatus.ACTIVE ? "0" : "1");
        if (tenantService.updateTenantStatus(bo) != 1) {
            throw new ServiceException("SAA-FND-004: 技术租户状态更新失败", 409);
        }
    }
}
