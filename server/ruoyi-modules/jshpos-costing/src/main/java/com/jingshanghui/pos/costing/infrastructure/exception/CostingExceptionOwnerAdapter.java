package com.jingshanghui.pos.costing.infrastructure.exception;

import com.jingshanghui.pos.costing.application.model.CostingCommands.RebuildBalance;
import com.jingshanghui.pos.costing.application.service.CostingService;
import com.jingshanghui.pos.costing.infrastructure.persistence.mapper.CostingExceptionMapper;
import com.jingshanghui.pos.costing.infrastructure.persistence.mapper.CostingExceptionMapper.CostingExceptionRow;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Costing Owner 序号缺口观察和从不可变成本流水重建的具名修复端口。 */
@Component @RequiredArgsConstructor
public class CostingExceptionOwnerAdapter implements OperationsExceptionOwnerPort {
    private final TrustedTenantContext tenants; private final ScopeAuthorizationService authorization;
    private final CostingExceptionMapper mapper; private final CostingService costing;
    @Override public String ownerCode(){return "COSTING";}
    @Override public List<OwnerObservation> scan(Long storeId,LocalDate date,int limit){authorization.requireStoreAccess(storeId);
        return mapper.listGaps(tenants.requireTenantId(),storeId,limit).stream().map(this::observation).toList();}
    @Override public OwnerRepairResult repair(OwnerRepairCommand c){authorization.requireStoreAccess(c.storeId());if(!"REBUILD_COST_BALANCE".equals(c.actionCode()))
        return new OwnerRepairResult("WAITING_OWNER",c.sourceFactId(),null,"成本异常必须使用具名重建且不得改写历史流水");
        CostingExceptionRow row=mapper.find(tenants.requireTenantId(),c.storeId(),c.sourceFactId());
        if(row==null)return new OwnerRepairResult("FAILED",c.sourceFactId(),null,"成本维度不存在或不可见");
        var result=costing.rebuild(new RebuildBalance(c.commandId(),row.warehouseId(),row.skuId(),c.correlationId()));
        String hash=CanonicalJson.from(Map.of("dimension",result.costDimensionKey(),"ledgerCount",result.ledgerCount(),"changed",result.changed())).sha256();
        return new OwnerRepairResult("SUCCEEDED",result.costDimensionKey(),hash,"成本余额已从只追加成本流水重建；历史成本未修改");}
    private OwnerObservation observation(CostingExceptionRow r){String hash=CanonicalJson.from(Map.of("dimension",r.dimensionKey(),"warehouseId",r.warehouseId(),"skuId",r.skuId(),"balanceSequence",r.balanceSequence(),"ledgerSequence",r.ledgerSequence(),"recordVersion",r.recordVersion())).sha256();
        return new OwnerObservation("COSTING_SEQUENCE_GAP",r.dimensionKey(),"cost-gap-"+hash.substring(0,24),r.ledgerSequence(),hash,"cost-"+r.dimensionKey(),"P1","cost-"+hash.substring(0,24),r.observedAt(),"成本余额与成本流水序号不一致","REBUILD_COST_BALANCE");}
}
