# ADR-051：独立 Operations Owner 的门店业务日日结与只追加签署事实

- 状态：Accepted
- 日期：2026-08-23
- 决策范围：T2-CLS-001

## 决策

新建 `jshpos-operations` 作为门店业务日日结唯一写入 Owner。它只写日结头、来源检查点、
冻结金额快照、预检结果、差异、审批、签署、状态事件、审计、幂等结果与 Outbox；不得
写入或跨 Mapper 查询 Order、Shift、Payment、Refund、Sync、Inventory、Costing、Promotion
或 Reporting 私有表。

Operations 通过 Foundation 门店模板端口取得可信 IANA 时区和业务日起点，通过 Order、
Payment、Sync、Reporting 的窄只读端口取得权威日事实与投影健康度。Reporting 仅用于
逐字段核对和来源事件检查点，不得成为覆盖业务事实的依据。Promotion、Inventory 与
Costing 的消费完整性由 Reporting 版本化事件血缘显式证明；缺失、乱序或摘要漂移均
失败关闭。

日结以 `tenant/store/business_date/close_version` 唯一标识；时区、业务日起点、Owner
检查点、内容 SHA-256、创建人、审批人和签署时间冻结。创建人与审批/签署人必须分离。
关闭签名与快照只追加，晚到事实不修改旧日结，而是追加差异并创建引用旧日结的新更正
版本。所有命令使用稳定幂等键；同键异内容拒绝。

## 失败关闭与非目标

- 未关班、未确认现金差异、UNKNOWN 支付/退款、同步积压/死信、投影缺口、摘要漂移、
  权限或审计缺失均阻止签署。
- 外部 Provider 未解阻时记录 `BLOCKED/UNAVAILABLE`，不伪造外部对账通过；内部现金
  日结可在不存在电子资金事实且其余检查通过时完成。
- 不包含异常修复工作台、真实 Provider 对账、真实设备命令、现场试点、生产或商业 SLA。
- 已发布 MySQL 迁移不可修改；失败使用前向修复，投影可重建，签署事实不可删除。
