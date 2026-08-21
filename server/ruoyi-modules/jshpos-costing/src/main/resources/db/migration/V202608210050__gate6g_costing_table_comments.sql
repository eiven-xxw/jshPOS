-- Gate 6G 成本表级中文元数据。
ALTER TABLE inv_cost_policy_version COMMENT = '仓级移动加权成本策略版本；租户隔离；发布后不可变';
ALTER TABLE inv_cost_balance COMMENT = '可由成本流水重建的成本投影；租户仓库SKU隔离';
ALTER TABLE inv_cost_ledger COMMENT = '不可变成本流水与出库成本快照；租户隔离；Costing Owner只追加';
ALTER TABLE inv_cost_rebuild_run COMMENT = '成本投影重建运行记录；租户隔离；受审计前向修复';
ALTER TABLE inv_cost_audit_event COMMENT = '成本关键操作审计；租户隔离；只追加';
ALTER TABLE inv_cost_event_outbox COMMENT = 'Costing Owner领域事件Outbox；租户隔离；只追加投递';
