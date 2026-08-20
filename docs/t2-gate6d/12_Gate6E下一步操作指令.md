# Gate 6E 下一步操作指令

> 仅在 Gate 6D 最终周门禁和治理收口 CI 全绿、项目发起人确认后使用。请把 `[Gate 6D 最终封板提交]` 替换为届时报告给出的完整 SHA。

```text
我确认《T2 Gate 6D / Sprint S15 周门禁报告》，接受 Gate 6D CONDITIONAL PASS。

同意将以下需求由 VERIFIED 更新为 ACCEPTED：

- T2-POS-007
- T2-POS-008
- T2-ADM-001
- T2-E2E-001

按 CONDITIONAL GO 启动 T2 Gate 6E / Sprint S16：后台运营第二波、原单退货退款界面与内部 Alpha 候选收口。

继续以 Gate 6D 最终封板提交
[Gate 6D 最终封板提交]
作为工作分支起点，并创建独立分支 t2/gate6e-sprint16-internal-alpha-candidate。

一、先在 RTM 中分配并按顺序逐项准入：

- T2-ADM-002：库存余额/流水、盘点、供应商/采购/收退货、调拨、成本、促销、会员、报表、终端与发布的正式后台运营 UI
- T2-POS-009：原单查询、可退数量/金额、原促销快照恢复、现金退货退款、Provider 无关退款状态和审计的正式 POS UI
- T2-E2E-002：仅基于虚构租户、虚构终端、现金和合成外部边界的内部 Alpha 候选闭环与回归收口

必须按 T2-ADM-002 → T2-POS-009 → T2-E2E-002 的顺序完成设计准入、实现和独立 VERIFIED，不得一次铺开。T2-E2E-002 只是 INTERNAL_ALPHA_CANDIDATE，不得更新或替代 T2-UAT-001。

二、实施要求：

1. 先冻结用户旅程、页面状态、数据主权、权限/数据范围、审计、错误码、API/事件、离线、容量、兼容、回退和测试准入，再写 UI；
2. Vue 只能调用各 Owner 正式 API/只读端口，禁止跨模块 Mapper、任意 SQL、前端状态机复制及金额/库存/成本/促销重算；
3. 后台写操作必须展示状态与版本、执行二次确认、使用稳定幂等键，并对审批、导出、回退、重建和失败恢复留下完整审计；
4. 成本页面只展示不可变成本流水和可重建投影，不允许人工覆盖历史成本；库存、采购、盘点和调拨效果仍只能由各 Owner 追加正式流水；
5. Flutter 退货退款 UI 只能通过 Return/Refund、Promotion、Payment、Inventory 和 Audit 的正式应用编排端口；禁止直接访问 SQLite、MethodChannel、Mapper 或拼装领域事实；
6. 原单退货必须读取原订单、原成交促销快照和累计已退上限；最后一次合法退款吸收余数，同幂等键异内容拒绝，UNKNOWN 只能查询/观察收敛，禁止重新生成退款命令；
7. 本 Sprint 只允许现金退款及既有 Provider 无关支付/退款核心的合成状态；T2-PAY-002 未解阻前不得创建 Provider SDK/HTTP、真实回调、账单下载或网络调用；
8. 内部 Alpha 候选至少覆盖后台初始化与发布 → POS 登录开班 → 销售/挂取单/现金成交 → 同步 → 库存/成本/报表 → 部分及最终退货退款 → 班次关闭 → 备份恢复与合成升级回退；
9. 使用两个虚构租户、多组织、多门店、多终端和三业态合成数据，覆盖跨租户/门店越权、重复/乱序、ACK 丢失、进程终止、磁盘失败、业务日切换、金额/摘要篡改、投影重建和安全前向修复；
10. 内部 Alpha 候选必须形成需求覆盖矩阵、P0/P1 缺陷账、失败 seed、证据目录、可重复运行手册和内部 Go/No-Go；任何 P0/P1 未关闭时保持 IN_PROGRESS/NO-GO；
11. 所有核心页面状态、应用服务、领域模型和重要实体保留有效中文注释；继续遵守 MyBatis-Plus/XML 双边界、Owner 数据主权与已发布迁移不可修改规则；
12. CI 必须包含治理、服务端、Web、Flutter Linux/Windows、Android/Kotlin、MySQL、SQLite、Widget/组件、退货退款故障、内部 Alpha 合成 E2E、租户权限、Secret、依赖、SBOM、许可证、覆盖率和 Gate 0—6D 全量回归；不得降低阈值、跳过失败测试、自动重跑掩盖 Flaky或创建绿色占位。

三、继续保持：

- T2-PAY-002、T2-HWD-001、T2-PAR-001 为 BLOCKED；
- T2-UAT-001、T2-REL-001 为 DRAFT；
- T2-JSH-001、T2-LIC-001 为 DEFERRED；
- Provider 网络、真实终端命令、现场试点和完整 Alpha UAT 为 0。

四、本 Sprint 继续不包含：

- 真实支付机构、回调、账单、真实资金与生产密钥；
- 真实打印、扫码、电子秤、钱箱、客显、APK 安装和厂商静默升级；
- 新增库存、采购、调拨、成本、促销、会员或报表领域算法；
- 优惠券、储值、发票、应付、总账、复杂 WMS、鲸熵汇及其他连接器；
- 真实 PII、现场试点、生产部署、完整 Alpha UAT 或商业可用声明。

完成后提交《T2 Gate 6E / Sprint S16 周门禁暨内部 Alpha 候选收口报告》，等待我确认，并为我整理外部 P0 恢复对接、完整 Alpha UAT 与发布准备的下一步操作指令。
```
