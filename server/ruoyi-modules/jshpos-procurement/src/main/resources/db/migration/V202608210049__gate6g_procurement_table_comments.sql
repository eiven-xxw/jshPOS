-- Gate 6G 采购表级中文元数据。
ALTER TABLE sup_supplier COMMENT = '供应商主数据；租户隔离；Procurement Owner受控写入';
ALTER TABLE pur_purchase_order COMMENT = '采购订单头及状态；租户隔离；订单本身不改库存';
ALTER TABLE pur_purchase_order_line COMMENT = '采购订单行冻结数量价格与单位换算；租户隔离';
ALTER TABLE pur_receipt COMMENT = '采购收货单及确认事实；租户隔离；确认后驱动库存流水';
ALTER TABLE pur_receipt_line COMMENT = '采购收货行及原单关系；租户隔离；精确数量';
ALTER TABLE pur_purchase_return COMMENT = '原收货采购退货单；租户隔离；审批后驱动库存流水';
ALTER TABLE pur_purchase_return_line COMMENT = '采购退货行及累计上限；租户隔离；精确数量';
ALTER TABLE pur_audit_event COMMENT = '采购关键操作审计；租户隔离；只追加';
ALTER TABLE pur_event_outbox COMMENT = 'Procurement Owner领域事件Outbox；租户隔离；只追加投递';
