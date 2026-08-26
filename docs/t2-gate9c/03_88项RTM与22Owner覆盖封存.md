# 88 项 RTM 与 22 Owner 覆盖封存

## 1. RTM

当前 T2 `ACCEPTED` 需求为 88 项。`T2-CMP-001` 复用现有 Requirement，不创建汇总型
重复需求。机器审计逐项核验 Requirement 到实现、证据和 Owner 资产的引用存在性。

## 2. Owner 清单

| 组别 | Owner |
|---|---|
| 基座与主数据 | Foundation、Catalog |
| 交易、资金与同步 | Order、Payment、Returns、Sync（含终端登记能力） |
| 供应链 | Inventory、Procurement、Transfer、Costing |
| 经营能力 | Promotion、Member、Reporting、Operations |
| 门店、可靠性与发布 | Migration、Onboarding、Resilience、Release |
| 商业运营 | SaaS、Subscription、Service |
| 内部编排 | Integration |

机器 Owner Catalog 是 `contracts/t2/gate9a-prep/owner-catalog-v1.json`。当前审计结果：
22/22 具备 Maven 模块、生产源码、测试、Admin 装配、Reactor 装配和依赖管理定位；
累计观察 272 个应用层文件、89 个领域文件、166 个持久化文件、88 个迁移文件和
285 个测试文件。数字用于当前仓库完整性复审，不构成代码质量或生产容量 SLA。

## 3. 数据主权

封板不改变各 Owner 写入边界。Controller、Vue、Flutter、Reporting、缓存或消息系统均不得
成为订单、支付、库存、成本、促销、会员或订阅的权威写入源。跨 Owner 继续使用正式端口、
版本化事件和 Inbox/Outbox，租户由可信上下文注入。
