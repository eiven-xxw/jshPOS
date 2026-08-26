# Owner 批量端口、Keyset 与流式导出设计

> 状态：`DESIGN_ONLY_AWAITING_RUNTIME_ADMISSION`。

## 版本化交互候选

- 候选入口：`GET /api/v2/reports/inventory-cost-daily`；
- v1 在明确兼容窗口内保持行为和响应冻结；
- 页大小默认 200，范围 1—500；
- 排序键固定为 `business_date,store_id,warehouse_id,sku_id,currency`；
- 游标使用 HMAC-SHA256 Secret 引用签名，绑定可信租户、投影版本、筛选摘要和最后键；
- 租户、筛选、投影版本、格式或签名漂移必须失败关闭。

## Reporting Owner 批量读取边界

候选端口为 `ReportingBatchReadPort.readInventoryCost`，仅接受服务端形成的可信租户、授权门店、
投影版本、日期范围、可选仓库/SKU、上一键、页界和请求摘要。端口不写业务事实，不跨 Owner
访问私有表。单次授权门店最多 50，单个导出分块最多 10,000 行。

## 受控流式导出

导出继续执行独立权限、审批阈值、字段脱敏、水印、时间/行数上限、审计、短期下载和到期清理。
恢复身份绑定 `tenant_id + export_id + request_sha256 + projection_version + cursor + byte_offset`；
重复请求返回稳定结果，同键异摘要拒绝。写入中断只保留隔离临时对象，完成摘要核验后才发布。

## 兼容与回退

新 v2/批量端口只能增量装配；v1 不原地改变。若新入口、游标、导出或计划验证失败，关闭新入口并
保留 v1，不回写投影、不修改权威事实、不删除历史导出审计。数据库结构只允许经独立 CR 的前向
迁移，禁止回滚迁移覆盖历史。
