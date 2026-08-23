# ADR-060：Gate 8B 商业 SaaS 运营内部汇总验收与证据边界

- 状态：Accepted
- 日期：2026-08-24
- 决策人：项目发起人、产品负责人、架构负责人、QA 负责人

## 背景

`T2-SAA-001`、`T2-SUB-001` 与 `T2-SVC-001` 已分别获得内部合成软件范围的接受，但尚未证明三者能经正式协议形成连续商业运营旅程。外部支付、硬件、外设、设计伙伴、完整 Alpha 与商业许可证也仍未关闭。

## 决策

1. Gate 8B-Prep 不新增商业领域能力，只聚合已接受 Owner 的 RTM、协议、迁移和证据。
2. 聚合旅程必须经 `SaasOperationsController`、`SubscriptionController`、`ServiceOperationsController` 的正式 HTTP 契约进入既有应用服务；禁止直接数据库写入、跨 Owner Mapper 和测试后门。
3. 聚合测试允许模拟已接受应用服务返回值，以验证 HTTP 路径、请求头、DTO、稳定身份与状态装配；Owner 的真实状态机和持久化结论继续引用各自原始测试及 MySQL 证据，不由该模拟升级。
4. 新增内容只允许测试、治理、契约、报告、检查脚本与 CI；禁止新增或修改正式运行时代码和已发布迁移。
5. 证据上限固定为 `INTERNAL_SYNTHETIC_API_JOURNEY`，不得解释为 `FULL_ALPHA`、`PRODUCTION` 或 `COMMERCIAL`。
6. 外部四项、UAT/发布与许可证/鲸熵汇状态保持原值，所有外部执行计数保持 0。

## 后果

- 可以在不等待外部资料的前提下发现商业运营内部装配缺口。
- 不能据此启动完整 Alpha、现场执行或生产发布。
- 如汇总审计发现新业务需求，必须建立独立 CR 和 Requirement ID，不得在 Gate 8B-Prep 顺带实现。

## 验证

- `CommercialSaasOperationsFormalApiE2ETest` 经三个正式 Controller 完成冻结旅程。
- Gate 8B 检查器拒绝运行时、迁移、外部状态和证据等级漂移。
- Ubuntu/Windows 治理、Server、MySQL、Web、Flutter/Android、安全与证据索引门禁全部通过后，方可形成准备阶段评审结论。
