package com.jingshanghui.pos.order.infrastructure.persistence.mapper;

import com.jingshanghui.pos.order.application.model.OrderViews.ApprovalView;
import com.jingshanghui.pos.order.application.model.OrderViews.IdempotencyView;
import com.jingshanghui.pos.order.application.model.OrderViews.InventoryLineView;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.application.model.OrderViews.PaymentLineView;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 所有 SQL 显式携带可信 tenant_id；Aspect 在 Mapper 前再次要求可信主体。 */
public interface OrderMapper {

    @Select("SELECT command_type commandType,command_id commandId,idempotency_key idempotencyKey,request_sha256 requestSha256,aggregate_id aggregateId,result_code resultCode,result_json resultJson FROM ord_idempotency WHERE tenant_id=#{tenantId} AND command_type=#{commandType} AND idempotency_key=#{key}")
    IdempotencyView findIdempotency(@Param("tenantId") String tenantId, @Param("commandType") String commandType,
                                    @Param("key") String key);

    @Insert("""
        INSERT INTO ord_idempotency(idempotency_id,tenant_id,command_type,command_id,idempotency_key,
          request_sha256,aggregate_id,result_code,result_json,created_at)
        VALUES(#{id},#{tenantId},#{commandType},#{commandId},#{key},#{requestHash},#{aggregateId},
          #{resultCode},CAST(#{resultJson} AS JSON),#{at})
        """)
    int insertIdempotency(@Param("tenantId") String tenantId, @Param("id") String id,
                          @Param("commandType") String commandType, @Param("commandId") String commandId,
                          @Param("key") String key, @Param("requestHash") String requestHash,
                          @Param("aggregateId") String aggregateId, @Param("resultCode") String resultCode,
                          @Param("resultJson") String resultJson, @Param("at") LocalDateTime at);

    @Insert("""
        INSERT INTO shf_shift(shift_id,tenant_id,store_id,terminal_id,cashier_user_id,cashier_name_snapshot,
          business_date,store_timezone,config_version,status,currency,opening_cash_minor,theoretical_cash_minor,opened_at)
        VALUES(#{shiftId},#{tenantId},#{storeId},#{terminalId},#{cashierId},#{cashierName},#{businessDate},
          #{timezone},#{configVersion},'OPEN','CNY',#{openingCash},#{openingCash},#{openedAt})
        """)
    int insertShift(@Param("tenantId") String tenantId, @Param("shiftId") String shiftId,
                    @Param("storeId") Long storeId, @Param("terminalId") String terminalId,
                    @Param("cashierId") Long cashierId, @Param("cashierName") String cashierName,
                    @Param("businessDate") LocalDate businessDate, @Param("timezone") String timezone,
                    @Param("configVersion") long configVersion, @Param("openingCash") long openingCash,
                    @Param("openedAt") LocalDateTime openedAt);

    @Select("""
        SELECT shift_id shiftId,store_id storeId,terminal_id terminalId,cashier_user_id cashierUserId,
          cashier_name_snapshot cashierNameSnapshot,business_date businessDate,store_timezone storeTimezone,
          config_version configVersion,status,currency,opening_cash_minor openingCashMinor,
          theoretical_cash_minor theoreticalCashMinor,actual_cash_minor actualCashMinor,
          difference_minor differenceMinor,approval_id approvalId,record_version recordVersion
        FROM shf_shift WHERE tenant_id=#{tenantId} AND shift_id=#{shiftId}
        """)
    ShiftView findShift(@Param("tenantId") String tenantId, @Param("shiftId") String shiftId);

    @Select("""
        SELECT shift_id shiftId,store_id storeId,terminal_id terminalId,cashier_user_id cashierUserId,
          cashier_name_snapshot cashierNameSnapshot,business_date businessDate,store_timezone storeTimezone,
          config_version configVersion,status,currency,opening_cash_minor openingCashMinor,
          theoretical_cash_minor theoreticalCashMinor,actual_cash_minor actualCashMinor,
          difference_minor differenceMinor,approval_id approvalId,record_version recordVersion
        FROM shf_shift WHERE tenant_id=#{tenantId} AND shift_id=#{shiftId} FOR UPDATE
        """)
    ShiftView lockShift(@Param("tenantId") String tenantId, @Param("shiftId") String shiftId);

    @Select("SELECT COALESCE(SUM(signed_amount_minor),0) FROM shf_cash_ledger WHERE tenant_id=#{tenantId} AND shift_id=#{shiftId}")
    long sumCashLedger(@Param("tenantId") String tenantId, @Param("shiftId") String shiftId);

    @Insert("""
        INSERT INTO shf_shift_approval(approval_id,tenant_id,shift_id,approver_user_id,reason_code,reason_text,
          theoretical_cash_minor,actual_cash_minor,difference_minor,expected_shift_version,status,approved_at)
        VALUES(#{approvalId},#{tenantId},#{shiftId},#{approverId},#{reasonCode},#{reasonText},#{theoretical},
          #{actual},#{difference},#{expectedVersion},'APPROVED',#{at})
        """)
    int insertApproval(@Param("tenantId") String tenantId, @Param("approvalId") String approvalId,
                       @Param("shiftId") String shiftId, @Param("approverId") Long approverId,
                       @Param("reasonCode") String reasonCode, @Param("reasonText") String reasonText,
                       @Param("theoretical") long theoretical, @Param("actual") long actual,
                       @Param("difference") long difference, @Param("expectedVersion") long expectedVersion,
                       @Param("at") LocalDateTime at);

    @Select("""
        SELECT approval_id approvalId,shift_id shiftId,approver_user_id approverUserId,status,
          theoretical_cash_minor theoreticalCashMinor,actual_cash_minor actualCashMinor,
          difference_minor differenceMinor,expected_shift_version expectedShiftVersion
        FROM shf_shift_approval WHERE tenant_id=#{tenantId} AND approval_id=#{approvalId} AND shift_id=#{shiftId}
        """)
    ApprovalView findApproval(@Param("tenantId") String tenantId, @Param("shiftId") String shiftId,
                              @Param("approvalId") String approvalId);

    @Update("""
        UPDATE shf_shift SET status='CLOSED',theoretical_cash_minor=#{theoretical},actual_cash_minor=#{actual},
          difference_minor=#{difference},approval_id=#{approvalId},closed_at=#{at},record_version=record_version+1
        WHERE tenant_id=#{tenantId} AND shift_id=#{shiftId} AND status='OPEN' AND record_version=#{version}
        """)
    int closeShift(@Param("tenantId") String tenantId, @Param("shiftId") String shiftId,
                   @Param("theoretical") long theoretical, @Param("actual") long actual,
                   @Param("difference") long difference, @Param("approvalId") String approvalId,
                   @Param("at") LocalDateTime at, @Param("version") long version);

    @Insert("""
        INSERT INTO ord_sales_order(order_id,tenant_id,local_order_no,store_id,terminal_id,shift_id,cashier_user_id,
          business_date,store_timezone,status,draft_disposition,payment_status,currency,gross_amount_minor,
          discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,received_amount_minor,catalog_version,
          price_version,industry_template_version,snapshot_schema_version,snapshot_json,snapshot_sha256,
          idempotency_key,request_sha256,occurred_at,record_version)
        VALUES(#{orderId},#{tenantId},#{localNo},#{storeId},#{terminalId},#{shiftId},#{cashierId},#{businessDate},
          #{timezone},'COMPLETED','ACTIVE','PAID','CNY',#{gross},0,0,#{receivable},#{receivable},#{catalogVersion},
          #{priceVersion},#{templateVersion},1,CAST(#{snapshotJson} AS JSON),#{snapshotHash},#{idemKey},#{requestHash},#{at},4)
        """)
    int insertCompletedOrder(@Param("tenantId") String tenantId, @Param("orderId") String orderId,
                             @Param("localNo") String localNo, @Param("storeId") Long storeId,
                             @Param("terminalId") String terminalId, @Param("shiftId") String shiftId,
                             @Param("cashierId") Long cashierId, @Param("businessDate") LocalDate businessDate,
                             @Param("timezone") String timezone, @Param("gross") long gross,
                             @Param("receivable") long receivable, @Param("catalogVersion") long catalogVersion,
                             @Param("priceVersion") long priceVersion, @Param("templateVersion") String templateVersion,
                             @Param("snapshotJson") String snapshotJson, @Param("snapshotHash") String snapshotHash,
                             @Param("idemKey") String idemKey, @Param("requestHash") String requestHash,
                             @Param("at") LocalDateTime at);

    @Insert("""
        INSERT INTO ord_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,barcode_value,
          product_name_snapshot,unit_id,unit_code,quantity,unit_price_minor,gross_amount_minor,discount_amount_minor,
          surcharge_amount_minor,payable_amount_minor,price_source)
        VALUES(#{lineId},#{tenantId},#{orderId},#{lineNo},#{skuId},#{skuCode},#{barcode},#{productName},
          #{unitId},#{unitCode},#{quantity},#{unitPrice},#{gross},0,0,#{payable},#{priceSource})
        """)
    int insertOrderLine(@Param("tenantId") String tenantId, @Param("orderId") String orderId,
                        @Param("lineId") String lineId, @Param("lineNo") int lineNo,
                        @Param("skuId") Long skuId, @Param("skuCode") String skuCode,
                        @Param("barcode") String barcode, @Param("productName") String productName,
                        @Param("unitId") Long unitId,
                        @Param("unitCode") String unitCode, @Param("quantity") BigDecimal quantity,
                        @Param("unitPrice") long unitPrice, @Param("gross") long gross,
                        @Param("payable") long payable, @Param("priceSource") String priceSource);

    @Insert("""
        INSERT INTO ord_state_history(history_id,tenant_id,order_id,command_id,from_status,to_status,
          aggregate_version,actor_user_id,reason_code,occurred_at)
        VALUES(#{historyId},#{tenantId},#{orderId},#{commandId},#{fromStatus},#{toStatus},#{version},
          #{actorId},#{reasonCode},#{at})
        """)
    int insertStateHistory(@Param("tenantId") String tenantId, @Param("historyId") String historyId,
                           @Param("orderId") String orderId, @Param("commandId") String commandId,
                           @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                           @Param("version") long version, @Param("actorId") Long actorId,
                           @Param("reasonCode") String reasonCode, @Param("at") LocalDateTime at);

    @Insert("""
        INSERT INTO ord_cash_payment(cash_payment_id,tenant_id,order_id,shift_id,status,currency,
          receivable_amount_minor,tendered_amount_minor,change_amount_minor,net_amount_minor,occurred_at)
        VALUES(#{paymentId},#{tenantId},#{orderId},#{shiftId},'SUCCEEDED','CNY',#{receivable},#{tendered},#{change},#{net},#{at})
        """)
    int insertCashPayment(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId,
                          @Param("orderId") String orderId, @Param("shiftId") String shiftId,
                          @Param("receivable") long receivable, @Param("tendered") long tendered,
                          @Param("change") long change, @Param("net") long net,
                          @Param("at") LocalDateTime at);

    @Insert("""
        INSERT INTO shf_cash_ledger(cash_ledger_id,tenant_id,shift_id,order_id,cash_payment_id,movement_type,
          signed_amount_minor,currency,business_date,occurred_at)
        VALUES(#{ledgerId},#{tenantId},#{shiftId},#{orderId},#{paymentId},'SALE_RECEIPT',#{amount},'CNY',#{businessDate},#{at})
        """)
    int insertCashLedger(@Param("tenantId") String tenantId, @Param("ledgerId") String ledgerId,
                         @Param("shiftId") String shiftId, @Param("orderId") String orderId,
                         @Param("paymentId") String paymentId, @Param("amount") long amount,
                         @Param("businessDate") LocalDate businessDate, @Param("at") LocalDateTime at);

    @Update("UPDATE shf_shift SET theoretical_cash_minor=theoretical_cash_minor+#{amount},record_version=record_version+1 WHERE tenant_id=#{tenantId} AND shift_id=#{shiftId} AND status='OPEN'")
    int addShiftCash(@Param("tenantId") String tenantId, @Param("shiftId") String shiftId,
                     @Param("amount") long amount);

    @Insert("INSERT INTO ord_print_job(print_job_id,tenant_id,order_id,status,template_version,payload_sha256,created_at) VALUES(#{jobId},#{tenantId},#{orderId},'PENDING',#{templateVersion},#{hash},#{at})")
    int insertPrintJob(@Param("tenantId") String tenantId, @Param("jobId") String jobId,
                       @Param("orderId") String orderId, @Param("templateVersion") String templateVersion,
                       @Param("hash") String hash, @Param("at") LocalDateTime at);

    @Insert("""
        INSERT INTO ord_event_outbox(event_id,tenant_id,stream_code,event_type,aggregate_type,aggregate_id,
          aggregate_version,correlation_id,payload_json,payload_sha256,delivery_state,available_at)
        VALUES(#{eventId},#{tenantId},#{stream},#{eventType},#{aggregateType},#{aggregateId},#{version},
          #{correlationId},CAST(#{payloadJson} AS JSON),#{payloadHash},'PENDING',#{at})
        """)
    int insertOutbox(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
                     @Param("stream") String stream, @Param("eventType") String eventType,
                     @Param("aggregateType") String aggregateType, @Param("aggregateId") String aggregateId,
                     @Param("version") long version, @Param("correlationId") String correlationId,
                     @Param("payloadJson") String payloadJson, @Param("payloadHash") String payloadHash,
                     @Param("at") LocalDateTime at);

    @Insert("""
        INSERT INTO ord_audit_event(audit_id,tenant_id,action_code,aggregate_type,aggregate_id,actor_user_id,
          approver_user_id,command_id,trace_id,before_status,after_status,amount_minor,currency,request_sha256,
          reason_code,occurred_at)
        VALUES(#{auditId},#{tenantId},#{action},#{aggregateType},#{aggregateId},#{actorId},#{approverId},
          #{commandId},#{traceId},#{beforeStatus},#{afterStatus},#{amount},#{currency},#{requestHash},#{reasonCode},#{at})
        """)
    int insertAudit(@Param("tenantId") String tenantId, @Param("auditId") String auditId,
                    @Param("action") String action, @Param("aggregateType") String aggregateType,
                    @Param("aggregateId") String aggregateId, @Param("actorId") Long actorId,
                    @Param("approverId") Long approverId, @Param("commandId") String commandId,
                    @Param("traceId") String traceId, @Param("beforeStatus") String beforeStatus,
                    @Param("afterStatus") String afterStatus, @Param("amount") Long amount,
                    @Param("currency") String currency, @Param("requestHash") String requestHash,
                    @Param("reasonCode") String reasonCode, @Param("at") LocalDateTime at);

    @Select("""
        SELECT order_id orderId,local_order_no localOrderNo,store_id storeId,terminal_id terminalId,
          shift_id shiftId,cashier_user_id cashierUserId,business_date businessDate,status,payment_status paymentStatus,
          currency,gross_amount_minor grossAmountMinor,receivable_amount_minor receivableAmountMinor,
          received_amount_minor receivedAmountMinor,snapshot_sha256 snapshotSha256,snapshot_json snapshotJson,
          record_version recordVersion,occurred_at occurredAt
        FROM ord_sales_order WHERE tenant_id=#{tenantId} AND order_id=#{orderId}
        """)
    OrderView findOrder(@Param("tenantId") String tenantId, @Param("orderId") String orderId);

    /** 查询原订单行的精确成交数量和金额，仅供受控支付只读端口使用。 */
    @Select("""
        SELECT line_id lineId,quantity,payable_amount_minor payableAmountMinor
        FROM ord_order_line
        WHERE tenant_id=#{tenantId} AND order_id=#{orderId}
        ORDER BY line_no ASC
        """)
    List<PaymentLineView> findPaymentLines(@Param("tenantId") String tenantId, @Param("orderId") String orderId);

    /** 查询库存效果所需的不可变成交行；只读且显式携带可信 tenant_id。 */
    @Select("""
        SELECT line_id lineId,sku_id skuId,unit_id unitId,quantity
        FROM ord_order_line
        WHERE tenant_id=#{tenantId} AND order_id=#{orderId}
        ORDER BY sku_id ASC,line_no ASC
        """)
    List<InventoryLineView> findInventoryLines(@Param("tenantId") String tenantId,
                                               @Param("orderId") String orderId);
}
