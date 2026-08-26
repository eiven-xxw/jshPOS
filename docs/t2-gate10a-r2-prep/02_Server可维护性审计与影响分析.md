# Server 可维护性审计与影响分析

## 当前事实

- 22 个 Owner、656 个正式生产 Java 文件，Owner Maven 依赖环为 0；
- 19 个文件不少于 400 行，分布在 13 个 Owner，最大 760 行；
- 当前 Finding 的问题不是“代码不能运行”，而是大型编排类缺少单调预算和可证明的职责边界；
- 静态扫描观察到 3 组跨文件八行重复候选；它们只用于人工定位，不能在没有行为金标时机械抽取公共工具。

| 行数 | Owner | 文件 |
|---:|---|---|
| 760 | Procurement | `ProcurementService.java` |
| 700 | Inventory | `LotInventoryService.java` |
| 658 | Transfer | `TransferService.java` |
| 607 | Procurement | `ReplenishmentService.java` |
| 581 | Returns | `ReturnOrchestrationService.java` |
| 556 | Migration | `BusinessMigrationService.java` |
| 547 | Catalog | `ShelfLabelService.java` |
| 537 | Costing | `CostingService.java` |
| 512 | Inventory | `InventoryLedgerService.java` |
| 487 | Catalog | `CatalogMapper.java` |
| 461 | Promotion | `PromotionTransactionService.java` |
| 459 | Onboarding | `OnboardingService.java` |
| 458 | Member | `MemberPointsService.java` |
| 453 | Payment | `TenderPlanService.java` |
| 445 | Order | `ShiftService.java` |
| 437 | Returns | `ExchangeOrchestrationService.java` |
| 415 | Operations | `ExceptionCenterService.java` |
| 414 | Member | `MemberProfileService.java` |
| 409 | Order | `OrderMapper.java` |

## 影响与停止线

第一风险顺序为采购、批次库存、调拨、补货、退货编排。拆分前必须冻结公开 API、错误码、
事务提交/回滚、幂等、审计、Outbox、Owner 调用和数据守恒金标。禁止把逻辑移动到 Controller、
Mapper 或通用工具类；禁止为降行数复制状态机或打破同事务事实。

建议对新增类设 400 行硬上限，对既有 19 类建立只减不增白名单，并优先消除超过 600 行的
极端类。具体运行时阈值须在 ADR-074 获接受后生效。
