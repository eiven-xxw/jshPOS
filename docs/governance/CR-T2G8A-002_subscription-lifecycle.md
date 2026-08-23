# CR-T2G8A-002：T2-SUB-001 订阅生命周期与受控降级

- 状态：PREPARED_DEPENDENCY_BLOCKED
- 日期：2026-08-23
- Requirement：T2-SUB-001
- 前置依赖：T2-SAA-001 `ACCEPTED`
- 本次证据：`STATIC_DESIGN_AND_CONTRACT_PREP`

## 1. 商业价值

订阅续期、宽限期、到期、恢复和数据保留是 SaaS 持续运营的必要能力，使套餐权益变化可解释、
可审计、可恢复。三业态复用同一生命周期，不按业态复制状态机。

## 2. 推荐边界

新建独立 `jshpos-subscription` Owner，独占订阅头、合同/订单不透明外部引用、版本化期限、
只追加状态事件、定时检查点、通知意图和 Outbox。它通过 SaaS 正式命令端口请求权益切换或
租户受控降级，禁止直接写 SaaS、Foundation、支付或业务表。

## 3. 状态、不变量与降级

状态固定为 `DRAFT、PENDING_ACTIVATION、ACTIVE、GRACE_PERIOD、SUSPENDED、EXPIRED、
TERMINATION_PENDING、TERMINATED、RESTORED`。每次续期、宽限、到期和恢复使用稳定幂等键、
内容摘要和预期版本；同键异内容拒绝，重复任务、乱序事件、时钟偏移和重启最终收敛。

到期只能切换版本化降级策略，禁止删除事实或使租户无法完成退款、支付/退款查询、日结、
对账、审计、备份恢复、法定导出、迁移和数据清除。`TERMINATED` 是逻辑终态事实，不代表
物理删除；恢复必须创建新状态事件并保留原期限链。

## 4. 非目标、风险与 Go/No-Go

不包含真实计费、扣款、Provider、资金、发票、应付、总账、税务、真实短信/邮件或商业赔付。
主要风险是调度重复、时间边界和误降级，需以业务时区、UTC 时间点、单调版本和人工修复控制。

当前结论为 `DEPENDENCY BLOCKED / DRAFT`。只有 T2-SAA-001 独立 `ACCEPTED` 且项目发起人
再次确认，才可进入 SUB 正式开发。
