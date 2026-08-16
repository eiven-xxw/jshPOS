package com.jingshanghui.pos.foundation.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.OrgUnitView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.FoundationRules;
import com.jingshanghui.pos.foundation.infrastructure.observability.FoundationMetrics;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.OrgUnitEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.OrgUnitMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OrgUnitService {

    private static final int MAX_DEPTH = 8;

    private final OrgUnitMapper orgUnitMapper;
    private final StoreMapper storeMapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final DomainAuditService auditService;
    private final FoundationMetrics metrics;

    @Transactional(readOnly = true)
    public List<OrgUnitView> list() {
        tenantContext.requirePrincipal();
        return orgUnitMapper.selectList(new LambdaQueryWrapper<OrgUnitEntity>()
                .orderByAsc(OrgUnitEntity::getTreeDepth)
                .orderByAsc(OrgUnitEntity::getUnitCode))
            .stream().filter(entity -> authorizationService.canAccessOrg(entity.getOrgUnitId()))
            .map(this::toView).toList();
    }

    @Transactional
    public OrgUnitView create(CreateOrgUnit command) {
        String tenantId = tenantContext.requireTenantId();
        int depth = 1;
        if (command.parentId() == null) {
            authorizationService.requireTenantAdministrator();
        } else {
            authorizationService.requireOrgAccess(command.parentId());
            OrgUnitEntity parent = requireOrg(command.parentId());
            ensureActive(parent);
            depth = parent.getTreeDepth() + 1;
        }
        if (depth > MAX_DEPTH) {
            throw new ServiceException("FND-ORG-003: 组织层级超过 8", 400);
        }
        OrgUnitEntity entity = new OrgUnitEntity();
        entity.setTenantId(tenantId);
        entity.setParentId(command.parentId());
        entity.setUnitCode(FoundationRules.requireCode(command.code()));
        entity.setUnitName(FoundationRules.requireName(command.name()));
        entity.setUnitType(FoundationRules.requireEnum(command.type(), FoundationRules.ORG_TYPES, "FND-ORG-004"));
        entity.setStatus("ACTIVE");
        entity.setTreeDepth(depth);
        entity.setVersion(0);
        orgUnitMapper.insert(entity);
        OrgUnitView after = toView(entity);
        auditService.append("ORG_UNIT_CREATED", "ORG_UNIT", entity.getOrgUnitId(), null, after,
            Map.of("code", entity.getUnitCode(), "status", entity.getStatus()));
        metrics.increment("org.create", "success");
        return after;
    }

    @Transactional
    public OrgUnitView update(Long orgUnitId, UpdateOrgUnit command) {
        authorizationService.requireOrgAccess(orgUnitId);
        OrgUnitEntity entity = requireOrg(orgUnitId);
        OrgUnitView before = toView(entity);
        if (!entity.getVersion().equals(command.version())) {
            metrics.increment("org.update", "conflict");
            throw new ServiceException("FND-ORG-005: 组织版本冲突", 409);
        }
        if (!Objects.equals(entity.getParentId(), command.parentId())) {
            ensureNoChildrenForMove(orgUnitId);
        }
        int depth = resolveDepthAndCheckCycle(orgUnitId, command.parentId());
        String status = FoundationRules.requireEnum(command.status(), FoundationRules.ACTIVE_STATUS, "FND-ORG-007");
        if ("INACTIVE".equals(status)) {
            ensureNoActiveChildren(orgUnitId);
        }
        entity.setParentId(command.parentId());
        entity.setUnitCode(FoundationRules.requireCode(command.code()));
        entity.setUnitName(FoundationRules.requireName(command.name()));
        entity.setUnitType(FoundationRules.requireEnum(command.type(), FoundationRules.ORG_TYPES, "FND-ORG-004"));
        entity.setStatus(status);
        entity.setTreeDepth(depth);
        if (orgUnitMapper.updateById(entity) != 1) {
            throw new ServiceException("FND-ORG-005: 组织版本冲突", 409);
        }
        OrgUnitView after = toView(entity);
        auditService.append("ORG_UNIT_UPDATED", "ORG_UNIT", orgUnitId, before, after,
            Map.of("code", entity.getUnitCode(), "status", entity.getStatus()));
        metrics.increment("org.update", "success");
        return after;
    }

    private int resolveDepthAndCheckCycle(Long orgUnitId, Long parentId) {
        if (parentId == null) {
            authorizationService.requireTenantAdministrator();
            return 1;
        }
        authorizationService.requireOrgAccess(parentId);
        OrgUnitEntity current = requireOrg(parentId);
        ensureActive(current);
        int parentDepth = current.getTreeDepth();
        for (int traversed = 0; traversed < MAX_DEPTH && current != null; traversed++) {
            if (orgUnitId.equals(current.getOrgUnitId())) {
                throw new ServiceException("FND-ORG-008: 组织层级出现循环", 400);
            }
            current = current.getParentId() == null ? null : orgUnitMapper.selectById(current.getParentId());
        }
        int depth = parentDepth + 1;
        if (depth > MAX_DEPTH) {
            throw new ServiceException("FND-ORG-003: 组织层级超过 8", 400);
        }
        return depth;
    }

    private void ensureNoActiveChildren(Long orgUnitId) {
        Long orgChildren = orgUnitMapper.selectCount(new LambdaQueryWrapper<OrgUnitEntity>()
            .eq(OrgUnitEntity::getParentId, orgUnitId)
            .eq(OrgUnitEntity::getStatus, "ACTIVE"));
        Long activeStores = storeMapper.selectCount(new LambdaQueryWrapper<StoreEntity>()
            .eq(StoreEntity::getOrgUnitId, orgUnitId)
            .eq(StoreEntity::getStatus, "ACTIVE"));
        if (orgChildren > 0 || activeStores > 0) {
            throw new ServiceException("FND-ORG-009: 存在有效子组织或门店，不能停用", 409);
        }
    }

    /**
     * Gate 0 不做子树批量重写。存在任意直接子组织时禁止移动，避免后代 tree_depth 失真；
     * 后续若准入子树移动，必须使用专用命令和整棵子树的原子更新协议。
     */
    private void ensureNoChildrenForMove(Long orgUnitId) {
        Long children = orgUnitMapper.selectCount(new LambdaQueryWrapper<OrgUnitEntity>()
            .eq(OrgUnitEntity::getParentId, orgUnitId));
        if (children > 0) {
            throw new ServiceException("FND-ORG-015: 存在子组织，Gate 0 禁止移动子树", 409);
        }
    }

    private OrgUnitEntity requireOrg(Long orgUnitId) {
        OrgUnitEntity entity = orgUnitMapper.selectById(orgUnitId);
        if (entity == null) {
            throw new ServiceException("FND-ORG-001: 组织不存在或不可见", 404);
        }
        return entity;
    }

    private void ensureActive(OrgUnitEntity entity) {
        if (!"ACTIVE".equals(entity.getStatus())) {
            throw new ServiceException("FND-ORG-002: 组织已停用", 409);
        }
    }

    private OrgUnitView toView(OrgUnitEntity entity) {
        return new OrgUnitView(entity.getOrgUnitId(), entity.getParentId(), entity.getUnitCode(),
            entity.getUnitName(), entity.getUnitType(), entity.getStatus(), entity.getTreeDepth(), entity.getVersion());
    }

    public record CreateOrgUnit(Long parentId, String code, String name, String type) {
    }

    public record UpdateOrgUnit(Long parentId, String code, String name, String type, String status, Integer version) {
    }
}
