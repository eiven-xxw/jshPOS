# CR-T2G8C-011：MTN-001 接受与 PERF-002 正式准入

## 决策

项目发起人接受 `T2-MTN-001 CONDITIONAL PASS`，同意其由 `VERIFIED` 更新为 `ACCEPTED`，并授权从精确封存提交 `262099bf788bcb6916af2480644883fa6c5aed49` 建立 `t2/gate8c-sprint26c-perf002-runtime`。在 ADR-065、容量模型、执行器、阈值与故障向量冻结后，`T2-PERF-002` 由 `DRAFT` 更新为 `IN_PROGRESS`。

## 范围与影响

- 仅关闭 `G8C-PERF-P1-001` 和 `G8C-PERF-P1-002`；允许修复本轮固定负载稳定复现的既有 P0/P1 性能缺陷。
- 不新增业务能力、数据库迁移、依赖、外部适配或商业 SLA；不得改变资金、库存、租户和其他 Owner 权威事实。
- `T2-RDY-001` 继续 DRAFT；PAY/HWD/PRN/PAR、UAT/REL、LIC/JSH 状态和全部外部零执行边界保持不变。

## Go/No-Go

`CONDITIONAL GO`。准入成立条件是 admission、capacity model、executor、threshold 和 fault vectors 五类机器可读契约完整且治理检查通过。任一工作负载缺少正确性断言、执行器指纹、原始样本或退化阈值时 NO-GO；性能结果不得替代完整 Alpha 或商业验收。
