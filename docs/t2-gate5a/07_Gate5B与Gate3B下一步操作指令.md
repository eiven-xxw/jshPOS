# Gate 5B 与 Gate 3B 下一步操作指令

> 文档编号：JSH-POS-T2-G5A-007  
> 使用条件：项目发起人确认《T2 Gate 5A / Sprint S9 周门禁报告》后复制执行

## 1. 建议决策

1. 接受 Gate 5A `CONDITIONAL PASS`，将 `T2-PRM-001`、`T2-PRM-002`、`T2-PRM-003` 从 `VERIFIED` 更新为 `ACCEPTED`。
2. 暂不直接开发会员和报表。先以 Gate 5B / Sprint S10 收口 POS、Promotion、Order、Cash/Refund、Inventory 之间的正式交易编排，消除“促销内核已通过但结算仍按零优惠落单”的 P0 差距。
3. Gate 5B 首先为新增跨域集成需求分配唯一 Requirement ID：`T2-POS-006`、`T2-ORD-003`、`T2-REF-002`。只有完成数据主权、事务边界、不变量、权限、审计、API/事件、迁移、兼容、容量、回退和测试准入后，才能从 `DRAFT` 进入实现。
4. Gate 3B-Prep 继续只核验真实资料；`T2-PAY-002` 保持 `BLOCKED`。现金和 Provider 无关内核可以用于内部闭环，但不能替代电子支付沙箱证据。
5. `T2-MEM-001..002`、`T2-RPT-001..002` 本阶段最多进行设计、契约和合成验收准备，继续 `DRAFT`；不得创建运行时或绿色占位。

## 2. 可直接复制的下一步指令

```text
我确认《T2 Gate 5A / Sprint S9 周门禁报告》，接受 Gate 5A CONDITIONAL PASS。

同意将以下需求由 VERIFIED 更新为 ACCEPTED：

- T2-PRM-001
- T2-PRM-002
- T2-PRM-003

T2-PAY-002 继续 BLOCKED。Gate 3B-Prep 继续只允许真实资料核验；未经我确认独立《支付沙箱解阻评审报告》，不得进行任何 Provider 网络调用。

按 CONDITIONAL GO 启动 T2 Gate 5B / Sprint S10。先在 RTM 中分配并逐项准入以下唯一 Requirement ID：

- T2-POS-006：POS 促销报价、受权人工优惠、成交快照、现金收款、订单事实与 Outbox 本地原子结算
- T2-ORD-003：服务端订单正式消费并绑定不可变促销快照，升级订单行与订单金额不变量
- T2-REF-002：原单退货退款按成交优惠快照恢复，并与退款、库存和审计 Owner 幂等编排

必须按 T2-POS-006 → T2-ORD-003 → T2-REF-002 的依赖顺序完成设计准入和实现，不得一次铺开。只有逐项完成数据主权、状态/冻结点、事务边界、不变量、权限、审计、API/事件、MySQL/SQLite 迁移、容量、兼容、回退和测试准入后，才可由 DRAFT 更新为 READY/IN_PROGRESS。

POS 结算必须在同一 SQLite 事务中冻结购物篮输入、规则包版本、报价 fingerprint、人工优惠审计引用、成交促销快照、订单及订单行、现金收款、班次现金效果和 Outbox；任一写入失败必须整体回滚。重启后只能使用原幂等键恢复，禁止重新生成业务命令或按新规则重算。

服务端 Order Owner 必须验证 Promotion Owner 的不可变快照身份、租户、门店、终端、业务日、订单绑定、内容摘要和金额。订单金额统一满足 gross - discount + surcharge = receivable，逐行合计必须与订单头一致；不得继续强制 discount=0，也不得由订单模块复制促销算法或直接写促销表。

跨 Owner 协作必须通过明确端口、版本化事件和 Inbox/Outbox；每个 Owner 只写自己的事实。Promotion Owner 写优惠快照/退款分摊，Order/Refund Owner 写订单或退货退款状态，Inventory Owner 写不可变退货入库流水，Payment Owner 只处理 Provider 无关退款状态。禁止跨模块 Mapper 直接更新其他 Owner 表。

原单退货退款必须读取原订单和原促销快照，按累计已退数量/金额上限分配；最后一次退款吸收合法余数。重复、乱序、ACK 丢失、进程终止、服务端已收客户端未知和部分 Owner 失败必须最终收敛；同幂等键异内容拒绝，UNKNOWN 不得通过重新发起业务命令解决。

本 Sprint 仅允许现金及既有 Provider 无关支付/退款核心的内部合成闭环。T2-PAY-002 未解阻前，禁止 Provider SDK/HTTP 客户端、真实回调端点、账单下载、真实终端、生产密钥和真实资金。

继续使用两个虚构租户、多门店、多终端和三类业态合成数据。覆盖正常成交、手工审批、抹零、挂取单后规则变化、业务日切换、重复提交、同键异内容、磁盘失败、事务中断、Outbox/ACK 丢失、跨租户快照替换、金额/摘要篡改、部分/最终退货、库存失败、退款 UNKNOWN 和全量重放；Java 与 Dart 对订单和退款金额必须逐字段一致。

CI 继续执行治理、服务端、Flutter Linux/Windows、MySQL、SQLite、跨端向量、交易故障、租户攻击、权限、Secret、依赖、SBOM、许可证、覆盖率和证据门禁；不得降低阈值、跳过失败测试、自动重跑掩盖 Flaky、修改已发布迁移或创建绿色占位。

T2-MEM-001、T2-MEM-002、T2-RPT-001、T2-RPT-002 本 Sprint 只允许领域模型、隐私/权限边界、API/事件、查询投影、合成向量和验收用例准备，继续保持 DRAFT，不允许正式运行时实现。

继续禁止支付 Provider 网络、生产密钥、真实资金、未脱敏数据、优惠券、积分、储值、会员运行时、报表运行时、预算抢占、应付、发票、总账、批次成本、复杂配送 WMS 和后续 Gate 正式编码；不得用 Fake 解除 SANDBOX、REAL_DEVICE 或 PILOT 阻断，不得宣称 Alpha、可试点或可商用。

完成后提交《T2 Gate 5B / Sprint S10 周门禁报告》，等待我确认，并为我整理下一步操作指令。
```
