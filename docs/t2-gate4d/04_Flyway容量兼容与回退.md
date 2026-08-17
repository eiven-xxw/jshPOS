# Gate 4D Flyway、容量、兼容与回退

## 1. 迁移计划

- V18：`inv_transfer_order/line/command/dispatch/dispatch_line/receipt/receipt_line/transit_ledger/audit_event/event_outbox`，复合租户外键、唯一幂等键、数量检查和在途流水不可变触发器。
- V19：`9200800` 段调拨菜单和按钮权限，使用幂等插入且不修改既有权限。
- 已发布 V1—V17 不得修改；V18/V19 在 closure 前写入 SHA-256 封印清单。

## 2. 容量与并发

- 单调拨单 1—500 行；API 查询分页 1—500；Outbox 与审计按租户、状态、业务日期建立索引。
- 同一调拨聚合使用 `record_version` 乐观锁；发出和收货行按 `sku_id + transfer_line_id` 稳定排序，库存/成本 Owner 继续按维度行锁串行。
- 在途流水只追加，日常对账按 `tenant_id + transfer_id` 和 `tenant_id + transfer_line_id + sequence` 查询；不得让报表或缓存成为唯一事实源。
- 10k 调拨行批量仅作为合成容量观察，不形成生产 SLA；500 行是商业 V1 单命令硬上限。

## 3. 兼容窗口

- 新表为 Expand 迁移；旧应用不读取也不写调拨表，可与新 Schema 共存。
- V18/V19 成功后不得回滚数据库脚本。应用回退可关闭调拨入口，但必须保留查询、未收货处理和人工 Runbook。
- 事件使用显式 v1 Schema；新增可选字段保持向后兼容，删除/改义必须新版本。

## 4. 前向修复

- 迁移失败：修正根因并新增更高版本迁移，禁止覆盖 V18/V19。
- 发出事务失败：调拨发出事实、库存、成本、在途、审计与 Outbox 整体回滚，可使用原 commandId 重试。
- 收货事务失败：目的库存、成本、收货事实和在途结转整体回滚，不得把单据手工改成已收货。
- 投影异常：从不可变发出、收货与差异流水重建并对账，版本锁切换；禁止改历史流水。
- 应用回退期间已发出单据：禁止新发出，保留收货/差异紧急处置路径或按 Runbook 升回兼容版本。
