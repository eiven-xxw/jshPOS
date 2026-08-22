package com.jingshanghui.pos.procurement.infrastructure.persistence.mapper;

import com.jingshanghui.pos.procurement.application.model.ProcurementViews.OrderHead;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.OrderLine;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReceiptHead;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReceiptLine;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReturnHead;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.Supplier;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ProcurementPersistenceParams.*;
import com.jingshanghui.pos.procurement.application.port.ProcurementCostSourcePort.ReceiptCostSource;
import com.jingshanghui.pos.procurement.application.port.ProcurementCostSourcePort.ReturnCostSource;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.math.BigDecimal;

/** 采购复杂锁与聚合 SQL 边界；每个查询显式包含 tenant_id。 */
public interface ProcurementMapper {

    /** 只返回已确认收货与冻结采购价格，复杂关联 SQL 位于 XML。 */
    ReceiptCostSource findConfirmedReceiptCostSource(@Param("tenantId") String tenantId,
                                                      @Param("receiptLineId") String receiptLineId);

    /** 只返回已入账原收货退货及其原收货行关系。 */
    ReturnCostSource findPostedReturnCostSource(@Param("tenantId") String tenantId,
                                                @Param("returnLineId") String returnLineId);

    int insertSupplier(SupplierWrite write);
    Supplier findSupplier(@Param("tenantId") String tenantId, @Param("supplierId") String supplierId);
    Supplier lockSupplier(@Param("tenantId") String tenantId, @Param("supplierId") String supplierId);
    int updateSupplierState(SupplierStateUpdate update);

    int insertOrder(OrderWrite write);
    int insertOrderLine(OrderLineWrite write);
    OrderHead findOrder(@Param("tenantId") String tenantId, @Param("orderId") String orderId);
    OrderHead lockOrder(@Param("tenantId") String tenantId, @Param("orderId") String orderId);
    String findOrderRequestHash(@Param("tenantId") String tenantId, @Param("orderId") String orderId);
    List<OrderLine> findOrderLines(@Param("tenantId") String tenantId, @Param("orderId") String orderId);
    OrderLine lockOrderLine(@Param("tenantId") String tenantId, @Param("orderId") String orderId,
                            @Param("orderLineId") String orderLineId);
    int updateOrderStatus(OrderStatusUpdate update);
    int updateOrderLineReceived(OrderLineReceivedUpdate update);
    int countIncompleteOrderLines(@Param("tenantId") String tenantId, @Param("orderId") String orderId);
    BigDecimal sumConfirmedInTransitBase(@Param("tenantId") String tenantId,
                                         @Param("warehouseId") String warehouseId,
                                         @Param("skuId") Long skuId,
                                         @Param("supplierId") String supplierId);

    int insertReceipt(ReceiptWrite write);
    int insertReceiptLine(ReceiptLineWrite write);
    ReceiptHead findReceipt(@Param("tenantId") String tenantId, @Param("receiptId") String receiptId);
    ReceiptHead lockReceipt(@Param("tenantId") String tenantId, @Param("receiptId") String receiptId);
    List<ReceiptLine> findReceiptLines(@Param("tenantId") String tenantId, @Param("receiptId") String receiptId);
    ReceiptLine lockReceiptLine(@Param("tenantId") String tenantId, @Param("receiptId") String receiptId,
                                @Param("receiptLineId") String receiptLineId);
    int confirmReceipt(ReceiptConfirm confirm);

    int insertReturn(ReturnWrite write);
    int insertReturnLine(ReturnLineWrite write);
    ReturnHead findReturn(@Param("tenantId") String tenantId,
                          @Param("purchaseReturnId") String purchaseReturnId);
    ReturnHead lockReturn(@Param("tenantId") String tenantId,
                          @Param("purchaseReturnId") String purchaseReturnId);
    List<com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReturnLine> findReturnLines(
        @Param("tenantId") String tenantId, @Param("purchaseReturnId") String purchaseReturnId);
    int updateReceiptLineReturned(ReceiptLineReturnedUpdate update);
    int updateReturnState(ReturnStateUpdate update);
    int postReturn(ReturnPost post);

    int insertAudit(AuditWrite write);
    int insertOutbox(OutboxWrite write);
}
