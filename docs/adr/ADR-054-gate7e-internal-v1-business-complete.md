# ADR-054：Gate 7E 商业 V1 内部汇总验收边界

- 状态：Accepted
- 日期：2026-08-23
- 决策范围：T2-E2E-004
- 工作基线：`feeecec5e1b438ba46f4225954e950d4e45ceb0c`

## 背景

Gate 7B—7D 已逐项补齐并由项目发起人接受 POS 交易运营、秤码与门店供应作业、社区超市
批次效期、日结异常中心和会员权益价格等内部能力。既有 `T2-E2E-003` 只覆盖 Gate 6G 时点
的核心现金闭环，不能证明 Gate 7 实际获批能力在同一提交、同一证据链中仍可装配、守恒和
恢复。外部支付、真实设备/打印和设计伙伴仍未解阻，因此本阶段只能形成内部候选。

## 决策

1. `T2-E2E-004` 是只读验收与证据编排 Requirement，不拥有订单、资金、库存、成本、
   促销、会员、日结或异常业务事实，不新增数据库表、迁移、Controller 或领域算法。
2. 汇总执行只使用正式 REST/API、应用服务、事件、Inbox/Outbox、Owner 端口以及正式
   MySQL/SQLite 迁移路径；禁止跨 Owner Mapper、直接写库、测试后门和伪造外部成功。
3. 执行模型固定为 `FORMAL_COMPONENT_EXECUTION_PLUS_CONTRACT_RECONCILIATION`：同一 CI
   Run 实际执行 Server、Web、Flutter 双平台、Android/Kotlin、MySQL、SQLite 和固定跨端
   向量，再按版本化契约核对三业态旅程、Owner 检查点、守恒和故障结果。
4. 使用两个虚构租户、多组织、多门店、多终端；便利店和零食折扣店的批次能力保持关闭，
   社区超市显式启用批次/效期。三业态复用同一状态机和算法，只由版本化模板选择能力。
5. P0/P1 缺陷必须为 0。固定 seed、必需测试、任一组件、摘要、租户边界、守恒或恢复证据
   缺失时失败关闭，不得通过重跑、跳过、降低阈值或绿色占位形成候选。
6. 性能只继承 `T2-PERF-001` 的固定合成规模和同类执行器趋势；不得写成生产容量、实机
   性能或商业 SLA。
7. `T2-E2E-004` 的证据上限为 `INTERNAL_V1_BUSINESS_COMPLETE_CANDIDATE`，不更新或替代
   `T2-UAT-001/T2-REL-001`，不得解释为 SANDBOX、REAL_DEVICE、PILOT、FULL_ALPHA、
   PRODUCTION 或 COMMERCIAL。

## 后果与验证

- `T2-SAA-001/SUB-001/SVC-001` 未获独立 CR，继续 `DRAFT`，不得为了汇总验收补建运行时。
- `T2-PAY-002/HWD-001/PRN-001/PAR-001` 继续 `BLOCKED`，`T2-JSH-001/LIC-001` 继续
  `DEFERRED`，所有外部执行保持 0。
- 同一 GitHub Actions Run 必须产生治理双平台、Server、Web、Flutter 双平台、MySQL、
  内部汇总、安全和最终证据索引制品；候选完成后只更新为 `VERIFIED`，等待项目发起人确认。

