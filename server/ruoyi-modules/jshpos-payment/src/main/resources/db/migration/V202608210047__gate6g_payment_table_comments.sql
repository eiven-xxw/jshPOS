-- Gate 6G Provider无关支付、退款和对账表级中文元数据。
ALTER TABLE pay_payment_intent COMMENT = 'Provider无关支付意图；租户隔离；Payment Owner唯一写入';
ALTER TABLE pay_payment_attempt COMMENT = '支付尝试与UNKNOWN查询标识；租户隔离；禁止自动二次扣款';
ALTER TABLE pay_refund COMMENT = '原单退款状态事实；租户隔离；金额数量上限冻结';
ALTER TABLE pay_refund_line COMMENT = '原单退款行累计占额；租户隔离；精确数量金额';
ALTER TABLE pay_provider_observation COMMENT = 'Provider查询回调账单观察事实；租户隔离；只追加合并';
ALTER TABLE pay_observation_dead_letter COMMENT = '冲突支付观察隔离区；租户隔离；人工审计处置';
ALTER TABLE pay_state_history COMMENT = '支付退款状态迁移历史；租户隔离；只追加';
ALTER TABLE pay_idempotency COMMENT = '支付退款命令幂等结果；租户隔离；同键异内容拒绝';
ALTER TABLE pay_reconciliation_run COMMENT = 'Provider无关对账批次；租户隔离；合成账单边界';
ALTER TABLE pay_statement_entry COMMENT = '内部账单条目事实；租户隔离；外部账单仍阻断';
ALTER TABLE pay_reconciliation_case COMMENT = '支付退款对账差异案件；租户隔离；受审计处置';
ALTER TABLE pay_audit_event COMMENT = '支付退款关键操作审计；租户隔离；只追加';
ALTER TABLE pay_event_outbox COMMENT = 'Payment Owner领域事件Outbox；租户隔离；只追加投递';
