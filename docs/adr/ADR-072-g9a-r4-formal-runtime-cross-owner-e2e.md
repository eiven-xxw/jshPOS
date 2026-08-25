# ADR-072：G9A-R4 同窗正式运行栈跨 Owner E2E

- 状态：Accepted
- 日期：2026-08-25
- 关联：`G9A-E2E-P1-001`、`T2-E2E-004`、`T2-E2E-005`、`T2-INT-001`

## 上下文

Gate 7E 已验证商业 V1 组件与文件 SQLite，Gate 8B 已验证 SaaS、Subscription、Service 的
正式 MySQL/Redis/JAR/HTTP 旅程，但 Flutter 仍连接测试内置 HTTP，两个证据窗口也没有形成
三业态、22 Owner 的同窗闭环。把两个历史报告拼接不能证明正式装配可共同运行和恢复。

## 决策

1. G9A-R4 使用当前提交构建的商业 JAR、Vue dist 与 Flutter POS，在同一 MySQL、Redis、文件
   SQLite 和同一受控运行窗口执行；所有制品记录 commit 与 SHA-256。
2. 商业运营前置、三业态初始化和业务事实只经正式 HTTP/API、应用端口、事件、Inbox/Outbox
   与 POS Repository 产生；禁止直接业务库写入、跨 Owner Mapper、测试内置 HTTP 和
   Mock/InMemory Owner。
3. Flutter 通过正式终端激活、员工登录与服务端 URL 连接商业 JAR，使用文件 SQLite 冻结
   订单、现金事实和 Outbox；故障恢复必须复用原幂等键和事件身份。
4. 22 Owner 以正式 API 观察、运行时装配守卫、Owner 事实检查点和版本化事件共同证明覆盖；
   检查点只读，不得反向修改 Owner 事实。
5. 12 组守恒和冻结故障 seed 是关闭 Finding 的硬门槛；同窗证据只能标记
   `INTERNAL_FORMAL_STACK_CROSS_OWNER_CANDIDATE`。

## 后果

- CI 成本增加，但可区分“组件分别通过”和“正式栈共同通过”。
- 外部 Provider、真实设备、外设与伙伴继续失败关闭，内部证据不提升外部等级。
- 如果必须新增业务语义、修改资金/库存/租户事实或改写迁移，立即停止并提交独立 CR。

## 验证

- 四个 R4-R0 失败回归可在基线复现；
- Flutter 证据中的服务端指纹与当前商业 JAR 一致，且测试进程不监听业务 HTTP 端口；
- 三业态、22 Owner、12 守恒和固定故障 seed 全部产生同一 `runId`；
- Ubuntu 正式栈、Windows 契约/Flutter 回归及既有完整 CI 全绿。
