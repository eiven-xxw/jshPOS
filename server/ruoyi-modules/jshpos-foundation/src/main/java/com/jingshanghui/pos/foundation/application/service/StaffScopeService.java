package com.jingshanghui.pos.foundation.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.StaffScopeView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.FoundationRules;
import com.jingshanghui.pos.foundation.infrastructure.observability.FoundationMetrics;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.OrgUnitEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StaffScopeEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.OrgUnitMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StaffScopeMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StaffScopeService {

    private final StaffScopeMapper staffScopeMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final StoreMapper storeMapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final DomainAuditService auditService;
    private final FoundationMetrics metrics;

    @Transactional(readOnly = true)
    public List<StaffScopeView> list(Long userId) {
        authorizationService.requireTenantAdministrator();
        return allForUser(userId).stream().map(this::toView).toList();
    }

    @Transactional
    public List<StaffScopeView> replace(Long userId, List<ScopeInput> inputs) {
        authorizationService.requireTenantAdministrator();
        if (userId == null || userId <= 0 || inputs == null || inputs.size() > 100) {
            throw new ServiceException("FND-RBAC-004: 员工或范围数量无效", 400);
        }
        String tenantId = tenantContext.requireTenantId();
        List<StaffScopeEntity> existing = allForUser(userId);
        List<StaffScopeView> before = existing.stream().map(this::toView).toList();
        Map<String, StaffScopeEntity> byKey = new HashMap<>();
        existing.forEach(scope -> byKey.put(key(scope.getScopeType(), scope.getOrgUnitId(), scope.getStoreId()), scope));
        Set<String> desired = new HashSet<>();
        for (ScopeInput input : inputs) {
            String type = validate(input);
            String key = key(type, input.orgUnitId(), input.storeId());
            if (!desired.add(key)) {
                throw new ServiceException("FND-RBAC-005: 重复员工数据范围", 400);
            }
            StaffScopeEntity entity = byKey.get(key);
            if (entity == null) {
                entity = new StaffScopeEntity();
                entity.setTenantId(tenantId);
                entity.setUserId(userId);
                entity.setScopeType(type);
                entity.setOrgUnitId(input.orgUnitId());
                entity.setStoreId(input.storeId());
                entity.setStatus("ACTIVE");
                entity.setVersion(0);
                staffScopeMapper.insert(entity);
            } else if (!"ACTIVE".equals(entity.getStatus())) {
                entity.setStatus("ACTIVE");
                staffScopeMapper.updateById(entity);
            }
        }
        for (StaffScopeEntity entity : existing) {
            if ("ACTIVE".equals(entity.getStatus())
                && !desired.contains(key(entity.getScopeType(), entity.getOrgUnitId(), entity.getStoreId()))) {
                entity.setStatus("REVOKED");
                staffScopeMapper.updateById(entity);
            }
        }
        List<StaffScopeView> after = allForUser(userId).stream().map(this::toView).toList();
        auditService.append("STAFF_SCOPE_REPLACED", "USER", userId, before, after,
            Map.of("activeCount", desired.size()));
        metrics.increment("scope.replace", "success");
        return after;
    }

    private String validate(ScopeInput input) {
        if (input == null) {
            throw new ServiceException("FND-RBAC-006: 员工范围不能为空", 400);
        }
        String type = FoundationRules.requireEnum(input.scopeType(), FoundationRules.SCOPE_TYPES, "FND-RBAC-007");
        if ("TENANT".equals(type) && (input.orgUnitId() != null || input.storeId() != null)) {
            throw new ServiceException("FND-RBAC-008: TENANT 范围不能带组织或门店", 400);
        }
        if ("ORG_SUBTREE".equals(type)) {
            if (input.orgUnitId() == null || input.storeId() != null || orgUnitMapper.selectById(input.orgUnitId()) == null) {
                throw new ServiceException("FND-RBAC-009: 组织范围无效或不可见", 400);
            }
        }
        if ("STORE".equals(type)) {
            if (input.storeId() == null || input.orgUnitId() != null || storeMapper.selectById(input.storeId()) == null) {
                throw new ServiceException("FND-RBAC-010: 门店范围无效或不可见", 400);
            }
        }
        return type;
    }

    private List<StaffScopeEntity> allForUser(Long userId) {
        return staffScopeMapper.selectList(new LambdaQueryWrapper<StaffScopeEntity>()
            .eq(StaffScopeEntity::getUserId, userId)
            .orderByAsc(StaffScopeEntity::getScopeType)
            .orderByAsc(StaffScopeEntity::getStaffScopeId));
    }

    private String key(String type, Long orgUnitId, Long storeId) {
        return type + ":" + (orgUnitId != null ? orgUnitId : storeId != null ? storeId : 0);
    }

    private StaffScopeView toView(StaffScopeEntity entity) {
        return new StaffScopeView(entity.getStaffScopeId(), entity.getUserId(), entity.getScopeType(),
            entity.getOrgUnitId(), entity.getStoreId(), entity.getStatus(), entity.getVersion());
    }

    public record ScopeInput(String scopeType, Long orgUnitId, Long storeId) {
    }
}
