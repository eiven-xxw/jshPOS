# Gate 7B 第二批 CR 与正式开发启动评审报告

## 1. 评审结论

`PREPARED / CONDITIONAL PASS RECOMMENDATION / AWAITING SPONSOR CONFIRMATION`。

仓库事实审计确认 `T2-EXG-001` 与 `T2-PAY-004` 是真实缺口，且可在不复制资金、库存、
订单、促销或退款状态机的前提下补全。独立 CR 建议将基础换货纳入商业 V1；两项需求
继续 `DRAFT`，没有新增运行时代码或数据库迁移。

## 2. 已冻结材料

- CR-T2G7B-009、ADR-044（Proposed）和 Owner/状态机/不变量/事务/恢复边界；
- 版本化 OpenAPI、换货/支付份额事件 Schema、统一错误码和页面离线行为；
- MySQL/SQLite 前向迁移登记、容量、兼容、回退和前向修复方案；
- 17 组固定故障向量、量化验收阈值和 EXG→PAY 串行门禁；
- 专用治理/跨平台/范围/证据 CI，禁止正式运行时和外部执行混入准备阶段。

## 3. Go 条件

1. 项目发起人接受独立 CR，并明确授权 EXG 正式开发；
2. ADR-044 转 `Accepted`，EXG 完成逐项准入后才进入 `IN_PROGRESS`；
3. EXG 独立 `VERIFIED` 并经确认后，PAY 才可进入准入和编码；
4. PAY 电子份额在 `T2-PAY-002` 解阻前必须失败关闭，Provider 网络仍为 0；
5. 任何 P0/P1、状态/金额不变量、租户隔离、迁移或完整 CI 失败均为 `NO-GO`。

## 4. 状态与证据边界

| 项目 | 结论 |
| --- | --- |
| `T2-POS-010/011`、`T2-ORD-004` | 项目发起人已接受为 `ACCEPTED`，仅内部软件证据 |
| `T2-EXG-001` / `T2-PAY-004` | `DRAFT / DRAFT`，运行时为 0 |
| `T2-PAY-002/HWD-001/PRN-001/PAR-001` | `BLOCKED` |
| `T2-UAT-001/REL-001` | `DRAFT` |
| `T2-JSH-001/LIC-001` | `DEFERRED` |
| Provider/真实资金/设备/外设/伙伴/Alpha/生产 | 全部为 0 |

## 5. CI 与封存证据

本报告首先作为候选评审材料提交；候选 GitHub CI 通过后，应在本节回填 commit、Run、
Job、Artifact 与 SHA-256，并以独立治理提交完成封存。CI 未通过前，本报告自动为
`IN_PROGRESS / NO-GO`，不得用局部检查或重跑单 Job 代替。

## 6. 最终建议

建议项目发起人确认后按 `T2-EXG-001 → T2-PAY-004` 串行开发。不得自动启动第二批
编码、Gate 7C、完整 Alpha、外部对接或生产发布。
