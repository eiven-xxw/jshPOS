-- Gate 6G 库存与盘点表级中文元数据。
ALTER TABLE inv_stock_policy_version COMMENT = '版本化负库存策略；租户仓库隔离；发布后不可变';
ALTER TABLE inv_stock_command COMMENT = '库存命令幂等登记；租户隔离；同键异内容拒绝';
ALTER TABLE inv_stock_balance COMMENT = '可由库存流水重建的数量投影；租户仓库SKU隔离';
ALTER TABLE inv_stock_ledger COMMENT = '不可变库存数量流水；租户仓库SKU隔离；Inventory Owner只追加';
ALTER TABLE inv_stock_anomaly COMMENT = '负库存及顺序异常事实；租户隔离；只追加告警';
ALTER TABLE inv_audit_event COMMENT = '库存关键操作审计；租户隔离；只追加';
ALTER TABLE inv_event_outbox COMMENT = 'Inventory Owner领域事件Outbox；租户隔离；只追加投递';
ALTER TABLE inv_stocktake COMMENT = '动态盘点快照与审批状态；租户仓库隔离；受控状态迁移';
ALTER TABLE inv_stocktake_line COMMENT = '盘点行账面快照与校准数量；租户隔离；不可覆盖历史事实';
ALTER TABLE inv_stocktake_count COMMENT = '盘点计数事实；租户隔离；只追加';
ALTER TABLE inv_stocktake_adjustment COMMENT = '盘点差异对应库存调整引用；租户隔离；幂等闭环';
