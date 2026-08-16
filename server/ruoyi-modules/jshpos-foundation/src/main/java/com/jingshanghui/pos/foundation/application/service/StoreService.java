package com.jingshanghui.pos.foundation.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.BusinessDateView;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.StoreView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.BusinessDay;
import com.jingshanghui.pos.foundation.domain.FoundationRules;
import com.jingshanghui.pos.foundation.infrastructure.observability.FoundationMetrics;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.OrgUnitEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.OrgUnitMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreMapper storeMapper;
    private final OrgUnitMapper orgUnitMapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final DomainAuditService auditService;
    private final FoundationMetrics metrics;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<StoreView> list() {
        tenantContext.requirePrincipal();
        return storeMapper.selectList(new LambdaQueryWrapper<StoreEntity>()
                .orderByAsc(StoreEntity::getStoreCode))
            .stream().filter(entity -> authorizationService.canAccessStore(entity.getStoreId()))
            .map(this::toView).toList();
    }

    @Transactional
    public StoreView create(CreateStore command) {
        String tenantId = tenantContext.requireTenantId();
        authorizationService.requireOrgAccess(command.orgUnitId());
        requireActiveOrg(command.orgUnitId());
        ZoneId zoneId = BusinessDay.requireZoneId(command.zoneId());
        StoreEntity entity = new StoreEntity();
        entity.setTenantId(tenantId);
        entity.setOrgUnitId(command.orgUnitId());
        entity.setPlatformDeptId(command.platformDeptId());
        entity.setStoreCode(FoundationRules.requireCode(command.code()));
        entity.setStoreName(FoundationRules.requireName(command.name()));
        entity.setZoneId(zoneId.getId());
        entity.setBusinessDayStart(requireStart(command.businessDayStart()));
        entity.setStatus("PREPARING");
        entity.setVersion(0);
        storeMapper.insert(entity);
        StoreView after = toView(entity);
        auditService.append("STORE_CREATED", "STORE", entity.getStoreId(), null, after,
            Map.of("code", entity.getStoreCode(), "status", entity.getStatus()));
        metrics.increment("store.create", "success");
        return after;
    }

    @Transactional
    public StoreView update(Long storeId, UpdateStore command) {
        authorizationService.requireStoreAccess(storeId);
        authorizationService.requireOrgAccess(command.orgUnitId());
        requireActiveOrg(command.orgUnitId());
        StoreEntity entity = requireStore(storeId);
        StoreView before = toView(entity);
        if (!entity.getVersion().equals(command.version())) {
            metrics.increment("store.update", "conflict");
            throw new ServiceException("FND-ORG-012: 门店版本冲突", 409);
        }
        entity.setOrgUnitId(command.orgUnitId());
        entity.setPlatformDeptId(command.platformDeptId());
        entity.setStoreCode(FoundationRules.requireCode(command.code()));
        entity.setStoreName(FoundationRules.requireName(command.name()));
        entity.setZoneId(BusinessDay.requireZoneId(command.zoneId()).getId());
        entity.setBusinessDayStart(requireStart(command.businessDayStart()));
        entity.setStatus(FoundationRules.requireEnum(command.status(), FoundationRules.STORE_STATUS, "FND-ORG-013"));
        if (storeMapper.updateById(entity) != 1) {
            throw new ServiceException("FND-ORG-012: 门店版本冲突", 409);
        }
        StoreView after = toView(entity);
        auditService.append("STORE_UPDATED", "STORE", storeId, before, after,
            Map.of("code", entity.getStoreCode(), "status", entity.getStatus()));
        metrics.increment("store.update", "success");
        return after;
    }

    @Transactional(readOnly = true)
    public BusinessDateView businessDate(Long storeId, Instant at) {
        authorizationService.requireStoreAccess(storeId);
        StoreEntity entity = requireStore(storeId);
        Instant instant = at == null ? clock.instant() : at;
        ZoneId zoneId = BusinessDay.requireZoneId(entity.getZoneId());
        MDC.put("storeId", String.valueOf(storeId));
        return new BusinessDateView(storeId, zoneId.getId(), entity.getBusinessDayStart(), instant,
            BusinessDay.calculate(instant, zoneId, entity.getBusinessDayStart()));
    }

    private LocalTime requireStart(LocalTime start) {
        if (start == null) {
            throw new ServiceException("FND-ORG-014: 营业日起点不能为空", 400);
        }
        return start.withSecond(0).withNano(0);
    }

    private void requireActiveOrg(Long orgUnitId) {
        OrgUnitEntity org = orgUnitMapper.selectById(orgUnitId);
        if (org == null || !"ACTIVE".equals(org.getStatus())) {
            throw new ServiceException("FND-ORG-001: 组织不存在、不可见或已停用", 404);
        }
    }

    private StoreEntity requireStore(Long storeId) {
        StoreEntity entity = storeMapper.selectById(storeId);
        if (entity == null) {
            throw new ServiceException("FND-ORG-010: 门店不存在或不可见", 404);
        }
        return entity;
    }

    private StoreView toView(StoreEntity entity) {
        return new StoreView(entity.getStoreId(), entity.getOrgUnitId(), entity.getPlatformDeptId(),
            entity.getStoreCode(), entity.getStoreName(), entity.getZoneId(), entity.getBusinessDayStart(),
            entity.getStatus(), entity.getVersion());
    }

    public record CreateStore(Long orgUnitId, Long platformDeptId, String code, String name,
                              String zoneId, LocalTime businessDayStart) {
    }

    public record UpdateStore(Long orgUnitId, Long platformDeptId, String code, String name,
                              String zoneId, LocalTime businessDayStart, String status, Integer version) {
    }
}
