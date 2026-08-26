# Owner 批量端口、Keyset 与流式导出设计

> 状态：`DESIGN_ONLY_AWAITING_RUNTIME_ADMISSION`。

## v2 交互候选

- 候选入口：`GET /api/v2/reports/payment-reconciliation`；v1 保持冻结兼容窗口；
- 页大小默认 200，最小 1、最大 500；
- 筛选：日期、已授权门店集合、差异类型、处理状态；排序固定为
  `business_date,reconciliation_id`；
- HMAC-SHA256 游标只保存最小游标信息，密钥来自 Secret 引用；
- 游标绑定可信租户、授权门店摘要、日期、筛选、读快照身份和最后键；任一漂移失败关闭；
- 首页冻结来源检查点与范围摘要，后续页检测投影漂移。无法证明同一读快照时返回
  `RPT-PAGE-STALE`，不得静默跳行或沿用坏游标。

## 两类 Owner 批量端口

1. `ReportingBatchReadPort.readPaymentReconciliation`：只读 Reporting 自有对账投影，接受服务端形成
   的可信租户、已授权门店、日期、筛选、快照身份、after、limit 和请求摘要；不写任何事实。
2. `ProviderNeutralPaymentFactBatchReadPort.readByReferences`：由 Payment Owner 实现，每次最多 500 个
   Provider 无关引用，只返回最小支付/退款事实；不返回密钥、完整报文、卡号或 Provider 私有载荷。

第一端口解决页面和导出的 50 次查询放大；第二端口解决 500 引用的 501 次查询放大。Reporting
不得用 Mapper 越过 Owner 边界，也不得把批量端口变成外部 Provider 访问入口。

## 受控流式导出

- 继续执行独立权限、审批阈值、字段白名单/脱敏、水印、100k 行上限、短期下载和完整审计；
- 10k 分布预期一批、100k 分布约 48,000 行预期五批；交互每页一次查询；
- 恢复身份绑定 `tenant_id + export_id + request_sha256 + snapshot_identity + cursor + byte_offset`；
- 每批写隔离临时对象并保存摘要/字节偏移/原游标；同键异摘要拒绝；
- 完成后重新校验快照和整体 SHA-256，只有两者通过才原子发布；快照漂移或摘要失败清理临时对象；
- 禁止导出 Provider 敏感字段、Secret、证书、支付令牌或不必要 PII。

## 兼容与回退

v2、两个批量端口与流式路径只能增量装配。若运行时验证失败，关闭新入口并保留 v1；不得删除
差异、处理人、审计或导出历史，不得回写 Payment/Refund 事实。索引或快照所需数据库对象必须走
独立 CR 和唯一前向迁移。
