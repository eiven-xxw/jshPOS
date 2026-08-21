# Gate 7A 证据索引

## 1. 权威输入

- 商业 V1 范围：`docs/连接器型商业收银经营平台_商业可落地优化方案_V3.0.md`；
- 业务与验收：`docs/详细设计/31`—`40`；
- T2 需求与既有接受状态：`docs/governance/rtm.csv`；
- 当前不可变起点：`d66c252587561428def95058d67bc830e391a9ab`；
- 既有核心审计器：`scripts/audit_t2_gate6g_core.py`。

## 2. 本 Gate 机器可读证据

| 证据 | 用途 |
|---|---|
| `contracts/t2/gate7a/gate7a-audit.json` | 基线、证据等级、状态保持和零执行边界 |
| `contracts/t2/gate7a/v1-capability-family-matrix.csv` | 既有能力族复用分类 |
| `contracts/t2/gate7a/v1-gap-register.csv` | 18 项差距、Gate 顺序、准入条件与证据上限 |
| `artifacts/t2/gate7a/reused-gate6g-core-audit.json` | 64 项 ACCEPTED、15 Owner、0 硬失败复核；本地产物不入 Git |
| `artifacts/t2/gate7a/evidence-index.json` | CI 生产者制品 SHA-256 总索引；由流水线生成 |

## 3. 人可读交付

1. `01_商业V1现有业务能力清单.md`；
2. `02_商业V1内部业务功能差距报告.md`；
3. `03_Gate7B至7E依赖图与逐步验收计划.md`；
4. `04_页面API_Owner数据表测试覆盖矩阵.md`；
5. `05_V1非目标与CR候选清单.md`；
6. `06_T2_Gate7A_SprintS19启动评审报告.md`；
7. `07_Gate7B第一批正式业务开发操作指令.md`；
8. 本证据索引。

## 4. 证据解释限制

- 源码未出现某运行时只能证明当前仓库中未发现该实现，不证明任何第三方产品也没有该能力；
- 64 项接受状态来自项目发起人既有确认，不因 Gate 7A 被重新升级或降级；
- 18 项只代表经仓库和权威文档确认的内部产品候选，不代表商业需求已经最终批准；
- Gate 7A 不运行 Provider、真实设备、伙伴、完整 Alpha 或生产环境，不能产生相应证据。

## 5. 待 CI 回填

候选提交、GitHub Run、Artifact ID、Artifact SHA-256、Ubuntu/Windows 结果与最终封存提交将在流水线结束后写入最终封板说明。回填不得改变 RTM 业务状态或证据等级。
