package com.jingshanghui.pos.foundation.application.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.OrgUnitEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StaffScopeEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.OrgUnitMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StaffScopeMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import com.jingshanghui.pos.foundation.infrastructure.security.PlatformPrivilegeSource;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ScopeAuthorizationService {

    private static final int MAX_DEPTH = 8;

    private final TrustedTenantContext tenantContext;
    private final PlatformPrivilegeSource platformPrivilegeSource;
    private final StaffScopeMapper staffScopeMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final StoreMapper storeMapper;

    public void requireTenantAdministrator() {
        tenantContext.requirePrincipal();
        if (!platformPrivilegeSource.isTenantAdministrator()) {
            throw new ServiceException("FND-RBAC-001: 需要租户管理员权限", 403);
        }
    }

    /** 租户创建前的商业开户必须由平台管理员执行。 */
    public void requirePlatformAdministrator() {
        if (!platformPrivilegeSource.isPlatformAdministrator()) {
            throw new ServiceException("FND-RBAC-004: 需要平台管理员权限", 403);
        }
    }

    public boolean canAccessOrg(Long orgUnitId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        if (platformPrivilegeSource.isTenantAdministrator()) {
            return existsOrg(orgUnitId);
        }
        List<StaffScopeEntity> scopes = activeScopes(principal.userId());
        if (scopes.stream().anyMatch(scope -> "TENANT".equals(scope.getScopeType()))) {
            return existsOrg(orgUnitId);
        }
        OrgUnitEntity current = orgUnitMapper.selectById(orgUnitId);
        if (current == null) {
            return false;
        }
        for (int depth = 0; depth < MAX_DEPTH && current != null; depth++) {
            Long candidateId = current.getOrgUnitId();
            if (scopes.stream().anyMatch(scope -> "ORG_SUBTREE".equals(scope.getScopeType())
                && candidateId.equals(scope.getOrgUnitId()))) {
                return true;
            }
            current = current.getParentId() == null ? null : orgUnitMapper.selectById(current.getParentId());
        }
        return false;
    }

    public boolean canAccessStore(Long storeId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        StoreEntity store = storeMapper.selectById(storeId);
        if (store == null) {
            return false;
        }
        if (platformPrivilegeSource.isTenantAdministrator()) {
            return true;
        }
        List<StaffScopeEntity> scopes = activeScopes(principal.userId());
        if (scopes.stream().anyMatch(scope -> "TENANT".equals(scope.getScopeType()))) {
            return true;
        }
        if (scopes.stream().anyMatch(scope -> "STORE".equals(scope.getScopeType())
            && storeId.equals(scope.getStoreId()))) {
            return true;
        }
        return canAccessOrg(store.getOrgUnitId());
    }

    public void requireOrgAccess(Long orgUnitId) {
        if (!canAccessOrg(orgUnitId)) {
            throw new ServiceException("FND-RBAC-002: 无组织数据范围权限", 403);
        }
    }

    public void requireStoreAccess(Long storeId) {
        if (!canAccessStore(storeId)) {
            throw new ServiceException("FND-RBAC-003: 无门店数据范围权限", 403);
        }
    }

    private boolean existsOrg(Long orgUnitId) {
        return orgUnitId != null && orgUnitMapper.selectById(orgUnitId) != null;
    }

    private List<StaffScopeEntity> activeScopes(Long userId) {
        return staffScopeMapper.selectList(new LambdaQueryWrapper<StaffScopeEntity>()
            .eq(StaffScopeEntity::getUserId, userId)
            .eq(StaffScopeEntity::getStatus, "ACTIVE"));
    }
}
