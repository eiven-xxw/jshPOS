# CR-T2G10A-013：RPT-SALES 分页与导出兼容性准备

- 日期：2026-08-26
- 查询：`RPT-SALES / ReportingPersistenceMapper.querySales`
- 状态：`PROPOSED_AWAITING_SPONSOR_RUNTIME_CONFIRMATION`

## 现状与价值

100k 固定分布返回 48,000 行并观察到全表扫描与 filesort；50 门店三类报表导出旅程放大为
150 次 JDBC 查询。需要把交互查询与导出读取分开治理，避免大响应和按门店线性放大。

## 候选兼容方案

- 现有列表契约先保留一个明确兼容窗口；新增版本化游标分页候选，稳定键为
  `business_date,store_id,terminal_id,cashier_id,currency`，游标同时绑定 tenant、筛选摘要、
  projection version 和业务日范围。
- 导出只允许走 Reporting Owner 的受控异步/流式读取端口，沿用导出审批、脱敏、水印、行数与
  时间范围上限；不得把全量结果返回 Vue 内存。
- 多门店范围只能来自可信数据权限，服务端批量端口按授权门店集合读取；客户端不得自报 tenant。

## 兼容、回退与停止线

旧客户端在兼容窗口内保持原响应；新契约必须使用新 operationId/版本并冻结错误码。回退为停止
新入口、继续旧入口，不删除事实或导出审计。任何金额口径、字段、排序业务含义或 300 API 封板
变化必须另行确认。本 CR 当前不批准 Controller/OpenAPI、SQL、索引或迁移变更。
