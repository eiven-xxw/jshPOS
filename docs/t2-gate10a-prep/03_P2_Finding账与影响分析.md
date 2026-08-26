# P2 Finding 账与影响分析

累计登记 10 项 P2，当前 R1 三项已由项目发起人确认为 `CLOSED_IN_GATE10A_R1`，仍开放 7 项；
P0/P1 保持为 0。机器权威为 `contracts/t2/gate10a-prep/findings-register-v1.json`。

| Finding | 批次 | 风险 | 允许的关闭方式 |
|---|---|---|---|
| G10A-CI-P2-001 | R1（已关闭） | Action Node 20/setup-java v4 生命周期 | Node24 兼容回归、精确 SHA、全门禁 |
| G10A-DEP-P2-001 | R1（已关闭） | 四栈升级候选/兼容风险无同窗快照 | 分生态升级清单、漏洞/许可证/回退 |
| G10A-SUP-P2-001 | R1（已关闭） | 77 工作流供应链步骤重复 | 单一版本账、活动/历史工作流治理 |
| G10A-MTN-P2-001 | R2 | 19 个大型 Java 类 | 先金标后职责拆分，不改业务语义 |
| G10A-SQL-P2-001 | R2 | 无跨 Owner 查询计划/N+1 回归 | MySQL 8.4 计划、索引和查询数预算 |
| G10A-RES-P2-001 | R2 | 资源斜率只覆盖短窗口 | 10分钟/24小时线程连接文件队列门禁 |
| G10A-POS-P2-001 | R3 | 18 个大型 Dart 文件 | Java/Dart/SQLite/Widget 金标保护拆分 |
| G10A-SQLITE-P2-001 | R3 | WAL/页/Outbox 多日增长未量化 | 保留边界、增长阈值、受控清理与恢复 |
| G10A-OBS-P2-001 | R4 | 22 Owner SLI/Trace/告警覆盖不完整 | 低基数指标、脱敏、故障告警闭环 |
| G10A-STB-P2-001 | R4 | 24/72小时与发布恢复同窗未完成 | 先24小时、再72小时、同提交复验 |

任何修复若需要改变资金、库存、租户、支付、同步、迁移语义，必须停止该 Finding，另行提交 CR 和唯一 Requirement ID。
