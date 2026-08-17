# jshpos-returns 模块约束

- 本模块独占 `ret_*` 退货申请、行、状态历史、Inbox/Outbox 和幂等结果；禁止直接写 `ord_*`、`pay_*`、`prm_*`、`inv_*`、`shf_*`。
- 促销恢复、现金退款、Provider 无关退款和退货入库只能调用对应 Owner 端口；同一跨 Owner 事件必须复用稳定 eventId 和内容摘要。
- UNKNOWN 只能继续查询或接收可信观察，禁止生成新的退款命令、退款 ID 或资金效果。
- 原单累计退货数量在本模块订单守卫锁内校验；金额必须完全采用 Promotion Owner 的原成交快照恢复结果，禁止复制促销算法。
- `tenant_id` 只来自 `TrustedTenantContext`；Mapper XML、Inbox、Outbox、导出和任务必须显式携带可信租户条件。
- 复杂锁、累计聚合、状态条件更新和只追加事实使用 Repository/Mapper XML，禁止 `SELECT *`、通用更新或删除。
- 核心服务、命令、状态机、持久化模型、复杂 SQL 与数据库字段必须具备有效中文注释。
- Provider SDK/HTTP、真实回调、账单下载、生产密钥和真实资金继续禁止；Fake 证据不得解除支付沙箱、实机或试点阻断。
