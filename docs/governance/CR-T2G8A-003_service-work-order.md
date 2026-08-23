# CR-T2G8A-003：T2-SVC-001 实施项目与服务工单

- 状态：APPROVED_RUNTIME
- 日期：2026-08-23
- Requirement：T2-SVC-001
- 前置依赖：T2-SAA-001、T2-SUB-001 `ACCEPTED`
- 本次证据：`STATIC_DESIGN_AND_CONTRACT_PREP`

## 1. 商业价值

标准实施检查单和服务工单让开户、迁移、培训、故障与关闭责任可追踪，是交付商业 SaaS 的
运营支撑能力。三业态共用工单核心，以服务目录/模板表达差异。

## 2. 推荐边界

新建独立 `jshpos-service` Owner，独占服务目录版本、实施项目、检查项、工单、认领/转派、
处理历史、附件不透明引用、内部时间目标、审计和 Outbox。它只能读取 SaaS/Subscription
公开快照，禁止直接改变租户、订阅、设备、支付或业务事实。

附件正文位于租户命名空间对象存储；数据库只保存摘要、对象引用、媒体类型、大小、上传者、
保留和清理状态。下载必须由服务端重新校验可信租户、门店范围和独立权限，短期签名链接不能
由客户端拼装。

## 3. 状态与不变量

实施项目按 `DRAFT、PREFLIGHTING、READY、IN_PROGRESS、BLOCKED、READY_TO_HANDOVER、
HANDED_OVER、CANCELLED`；工单按 `OPEN、ASSIGNED、IN_PROGRESS、WAITING_INPUT、RESOLVED、
CLOSED、REOPENED、CANCELLED`。实施项目移交必须完成全部必选检查项；工单关闭必须已有
解决摘要并由不同于解决人的复核人执行。重开追加事实，不覆盖历史。认领使用租约和乐观锁，
同键异内容拒绝。

## 4. 非目标、风险与 Go/No-Go

内部目标时间不形成合同 SLA、赔付或自动升级承诺。不包含呼叫中心、在线聊天、远程控制、
AI 自动派单、第三方工单连接器、伙伴现场授权或生产变更审批。

项目发起人已于 2026-08-24 接受 SAA、SUB 前置证据并单独授权 S24-C；当前结论为
`CONDITIONAL GO / IN_PROGRESS`。执行范围严格受 ADR-059、原子 RTM 和外部零执行边界约束。
