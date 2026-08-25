# CR-T2G9R4-003：G9A-R4 正式运行栈跨 Owner 整改准入

- 日期：2026-08-25
- 状态：APPROVED_RUNTIME_REMEDIATION_IN_PROGRESS
- 基线：`059f47ebd6877b683345d1e6f7c0cd9a18d712b5`
- 分支：`t2/gate9b-sprint27i-g9a-r4-runtime`
- Finding：`G9A-E2E-P1-001`
- 复用需求：`T2-E2E-004/T2-E2E-005/T2-INT-001`

## 批准范围

项目发起人接受准备阶段 `CONDITIONAL PASS`，只批准按 R4-R0 至 R4-R5 关闭既有正式栈
联合证据缺口。允许新增测试、运行编排、只读证据收集、最小装配缺陷修复、治理文档和 CI；
不允许新增业务能力、Requirement ID、生产表、已发布迁移变更或外部执行。

## 停止条件

- 需要改变资金、库存、租户、支付 `UNKNOWN` 或历史事实语义；
- 需要直接写业务数据库、引入测试后门或跨 Owner Mapper；
- 需要 Provider 网络、真实资金、设备/外设命令或伙伴现场；
- P0/P1 无法在既有需求范围内修复。

触发任一条件时保持 Finding `OPEN` 并单独提交 CR。
