-- Gate 6G 班次、订单和现金事实表级中文元数据。
ALTER TABLE shf_shift COMMENT = '收银班次及现金核对状态；租户门店终端隔离；Order Owner受控写入';
ALTER TABLE shf_shift_approval COMMENT = '班次差异审批事实；租户隔离；只追加审计链';
ALTER TABLE ord_sales_order COMMENT = '不可变销售订单头快照；租户隔离；Order Owner唯一写入';
ALTER TABLE ord_order_line COMMENT = '不可变销售订单行快照；租户隔离；金额数量守恒';
ALTER TABLE ord_state_history COMMENT = '订单状态迁移历史；租户隔离；只追加';
ALTER TABLE ord_cash_payment COMMENT = '现金收款与退款事实；租户班次绑定；只追加';
ALTER TABLE shf_cash_ledger COMMENT = '班次现金流水；租户隔离；只追加可对账';
ALTER TABLE ord_print_job COMMENT = '成交打印任务事实；租户终端隔离；不代表实机打印';
ALTER TABLE ord_event_outbox COMMENT = 'Order Owner领域事件Outbox；租户隔离；只追加投递';
ALTER TABLE ord_idempotency COMMENT = '订单与班次命令幂等结果；租户隔离；同键异内容拒绝';
ALTER TABLE ord_audit_event COMMENT = '订单与现金关键操作审计；租户隔离；只追加';
