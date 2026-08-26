# CR-T2G10A-015：RPT-PAY-REC 分页与导出兼容性准备

- 日期：2026-08-26
- 查询：`RPT-PAY-REC / PaymentReconciliationMapper.query`
- 状态：`PROPOSED_AWAITING_SPONSOR_RUNTIME_CONFIRMATION`

## 现状与价值

100k 固定分布返回 48,000 行并观察到 filesort；按门店导出参与 150 次查询放大。候选整改必须
继续保持 Provider 无关、支付 `UNKNOWN` 只观察原命令以及外部支付阻断。

## 候选兼容方案

- 交互候选采用绑定 tenant、授权门店、业务日、差异状态和处理状态摘要的稳定游标，排序键冻结为
  `business_date,reconciliation_id`，不得用客户端状态覆盖 Payment/Refund 权威事实。
- 导出由 Reporting Owner 通过 Payment 的明确只读端口/版本化投影批量读取；保留字段脱敏、审批、
  水印、行数上限、短期下载和审计，禁止输出支付敏感数据。
- 当前只覆盖内部 Provider 无关事实；真实账单下载、SDK/HTTP、回调和资金继续 BLOCKED。

## 兼容、回退与停止线

旧契约保留兼容窗口，新入口独立版本化；回退不得删除差异、处理人或审计链。支付状态、金额、
对账归并、外部证据等级或 300 API 封板变化必须独立评审。本 CR 当前不批准运行时或网络调用。
