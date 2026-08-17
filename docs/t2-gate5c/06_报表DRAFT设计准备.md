# T2-RPT-001/002 报表 DRAFT 设计准备

本文不准入任何 `rpt_*` 表、Controller、Job、Mapper 或运行时。

- 销售/收银来自 Order/Shift/Promotion 权威事实；库存/成本来自 Inventory/Costing；支付/退款来自 Payment，Provider 账单仍被 T2-PAY-002 阻断。
- Member 只提供 member_id、等级代码和脱敏聚合维度，不提供身份明文。
- 投影保存 source event ID/hash、schema version、business date 和 projection version；重复幂等，同键异内容拒绝，晚到可补算，投影可丢弃重建。
- 查询强制 tenant + org/store 范围；导出有审批、数量上限、水印、脱敏、对象存储租户命名空间和审计。
- 合成向量覆盖重复、乱序、晚到、全量重建、跨租户查询、越权导出、PII 泄漏和支付账单缺失；只是 DRAFT 验收输入。
