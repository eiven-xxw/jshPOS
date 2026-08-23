package com.jingshanghui.pos.inventory.infrastructure.exception;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.RebuildBalance;
import com.jingshanghui.pos.inventory.application.service.InventoryLedgerService;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.InventoryExceptionMapper;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.InventoryExceptionMapper.InventoryExceptionRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Inventory Owner 负库存事实与可重建余额修复端口；历史流水永不改写。 */
@Component @RequiredArgsConstructor
public class InventoryExceptionOwnerAdapter implements OperationsExceptionOwnerPort {
    private final TrustedTenantContext tenants; private final ScopeAuthorizationService authorization;
    private final InventoryExceptionMapper mapper; private final InventoryLedgerService inventory;
    @Override public String ownerCode(){return "INVENTORY";}
    @Override public List<OwnerObservation> scan(Long storeId, LocalDate businessDate,int limit){authorization.requireStoreAccess(storeId);
        return mapper.listOpen(tenants.requireTenantId(),storeId,limit).stream().map(this::observation).toList();}
    @Override public OwnerRepairResult repair(OwnerRepairCommand c){
        authorization.requireStoreAccess(c.storeId());
        if(!"REBUILD_INVENTORY_BALANCE".equals(c.actionCode())) return new OwnerRepairResult("WAITING_OWNER",c.sourceFactId(),null,"库存异常需要受审计调整事实或具名余额重建");
        InventoryExceptionRow row=mapper.find(tenants.requireTenantId(),c.storeId(),c.sourceFactId());
        if(row==null)return new OwnerRepairResult("FAILED",c.sourceFactId(),null,"库存异常不存在或不可见");
        var result=inventory.rebuild(new RebuildBalance(row.warehouseId(),row.skuId(),c.correlationId()));
        String hash=CanonicalJson.from(Map.of("dimension",result.dimensionKey(),"quantity",result.ledgerQuantity().toPlainString(),"ledgerCount",result.ledgerCount())).sha256();
        return new OwnerRepairResult("SUCCEEDED",result.dimensionKey(),hash,"库存余额已从不可变流水重建；历史流水未修改");
    }
    private OwnerObservation observation(InventoryExceptionRow r){String hash=CanonicalJson.from(Map.of("anomalyId",r.anomalyId(),"warehouseId",r.warehouseId(),"skuId",r.skuId(),"type",r.anomalyType(),"quantity",r.observedQuantity(),"sourceEventId",r.sourceEventId())).sha256();
        return new OwnerObservation(r.anomalyType(),r.anomalyId(),r.sourceEventId(),0,hash,"inventory-"+r.anomalyId(),"P1",r.sourceEventId(),r.occurredAt(),"库存异常="+r.anomalyType(),"REBUILD_INVENTORY_BALANCE");}
}
