# CR-T2G10A-014：RPT-INVENTORY 分页与导出兼容性准备

- 日期：2026-08-26
- 查询：`RPT-INVENTORY / ReportingPersistenceMapper.queryInventoryCost`
- 状态：`RUNTIME_IN_PROGRESS_INDEX_NOT_ADMITTED`
- 准备起点：`206b07f93014a468102380577a987ac85af1ceff`
- 准备分支：`t2/gate10a-r2-r2-r2-r2-rpt-inventory-prep`
- 准备封存提交：`f36df63b21bd3bb98ea0d5022f8fe5fac5def72f`
- 运行分支：`t2/gate10a-r2-r2-r2-r2-rpt-inventory-runtime`

## 现状与价值

100k 固定分布返回 48,000 行并观察到全表扫描与 filesort；50 门店库存成本导出当前执行 50 次
查询（原三类报表合计红基线为 150 次）。
需要在不改变库存、成本权威事实和报表口径的前提下限制交互响应并提供受控导出。

## 候选兼容方案

- 交互候选采用绑定 `tenant_id + projection_version + filters_sha256` 的游标分页，稳定键为
  `business_date,store_id,warehouse_id,sku_id,currency`；禁止 offset 深分页作为唯一方案。
- 导出由 Reporting Owner 批量读取授权门店，流式写入受控对象存储；保留摘要、脱敏、水印、
  审批、到期清理和可重放检查点。
- 报表层不重新计算在手、可用、成本或 COGS，不反向覆盖 Inventory/Costing 事实。

## 当前运行时授权

项目发起人已于 2026-08-27 接受准备阶段 `CONDITIONAL PASS`，并从唯一准备封存提交授权
RPT-INVENTORY 运行时精确整改。允许先补冻结失败测试，再串行实现 Reporting Owner 库存成本
批量读取端口、独立 v2 keyset 分页、HMAC 签名游标、受控流式导出与原身份恢复。

## 兼容、回退与停止线

旧契约保留兼容窗口，新入口独立版本化；回退不得改写投影或权威事实。字段、数量/成本口径、
投影版本语义或数据范围变化必须停止并单独评审。本 CR 不批准索引或迁移变更；固定 MySQL 8.4.11
计划若证明需要索引，必须立即停止并提交独立索引 CR 与唯一前向迁移方案。
