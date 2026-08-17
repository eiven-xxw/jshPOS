package com.jingshanghui.pos.transfer.infrastructure.persistence.mapper;

import com.jingshanghui.pos.transfer.application.model.TransferViews.*;
import com.jingshanghui.pos.transfer.application.port.TransferCostSourcePort.DispatchCostSource;
import com.jingshanghui.pos.transfer.application.port.TransferCostSourcePort.ReceiptCostSource;
import com.jingshanghui.pos.transfer.infrastructure.persistence.TransferPersistenceParams.*;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/** 调拨锁、聚合与成本来源 SQL 边界；每条 SQL 必须显式限定 tenant_id。 */
public interface TransferMapper {
    int insertOrder(OrderWrite value);
    int insertLine(LineWrite value);
    TransferHead findOrder(@Param("tenantId") String tenantId, @Param("transferId") String transferId);
    TransferHead lockOrder(@Param("tenantId") String tenantId, @Param("transferId") String transferId);
    String findOrderRequestHash(@Param("tenantId") String tenantId, @Param("transferId") String transferId);
    List<TransferLine> findLines(@Param("tenantId") String tenantId, @Param("transferId") String transferId);
    TransferLine lockLine(@Param("tenantId") String tenantId, @Param("transferId") String transferId,
                          @Param("transferLineId") String transferLineId);
    int updateStatus(StatusUpdate value);
    int updateLineProgress(LineProgress value);

    int insertCommand(CommandWrite value);
    String findCommandHash(@Param("tenantId") String tenantId, @Param("commandId") String commandId);
    String findCommandStatus(@Param("tenantId") String tenantId, @Param("commandId") String commandId);
    int markCommandApplied(CommandApplied value);

    int insertDispatch(DispatchWrite value);
    int insertDispatchLine(DispatchLineWrite value);
    DispatchHead findDispatchByTransfer(@Param("tenantId") String tenantId,
                                        @Param("transferId") String transferId);
    List<DispatchLine> findDispatchLines(@Param("tenantId") String tenantId,
                                         @Param("transferId") String transferId);
    int insertReceipt(ReceiptWrite value);
    int insertReceiptLine(ReceiptLineWrite value);
    ReceiptHead findReceipt(@Param("tenantId") String tenantId, @Param("receiptId") String receiptId);

    DispatchCostSource findPostedDispatchCostSource(@Param("tenantId") String tenantId,
                                                    @Param("dispatchLineId") String dispatchLineId);
    ReceiptCostSource findPostedReceiptCostSource(@Param("tenantId") String tenantId,
                                                  @Param("receiptLineId") String receiptLineId);

    int insertTransit(TransitWrite value);
    BigDecimal sumTransit(@Param("tenantId") String tenantId, @Param("transferLineId") String transferLineId,
                          @Param("factType") String factType);
    int insertAudit(AuditWrite value);
    int insertOutbox(OutboxWrite value);
}
