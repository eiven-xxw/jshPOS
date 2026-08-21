-- Gate 6G 调拨表级中文元数据。
ALTER TABLE inv_transfer_order COMMENT = '仓间调拨申请与状态；同租户范围；Transfer Owner唯一写入';
ALTER TABLE inv_transfer_line COMMENT = '调拨行冻结数量与来源目的；租户隔离；精确数量';
ALTER TABLE inv_transfer_command COMMENT = '调拨命令幂等登记；租户隔离；同键异内容拒绝';
ALTER TABLE inv_transfer_dispatch COMMENT = '调拨发出事实；租户隔离；关联来源库存与成本快照';
ALTER TABLE inv_transfer_dispatch_line COMMENT = '调拨发出行及成本引用；租户隔离；只追加事实';
ALTER TABLE inv_transfer_receipt COMMENT = '调拨收货与差异状态；租户隔离；受控状态迁移';
ALTER TABLE inv_transfer_receipt_line COMMENT = '调拨收货行数量和来源发出引用；租户隔离';
ALTER TABLE inv_transfer_transit_ledger COMMENT = '不可变调拨在途流水；租户隔离；可重建对账';
ALTER TABLE inv_transfer_audit_event COMMENT = '调拨关键操作审计；租户隔离；只追加';
ALTER TABLE inv_transfer_event_outbox COMMENT = 'Transfer Owner领域事件Outbox；租户隔离；只追加投递';
