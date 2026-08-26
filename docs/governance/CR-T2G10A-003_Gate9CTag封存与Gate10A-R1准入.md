# CR-T2G10A-003：Gate 9C tag 封存与 Gate 10A-R1 准入

## 决策

项目发起人于 2026-08-26 完成两项独立授权：

1. 创建并推送 annotated tag `t2-internal-product-completeness-seal-2026-08-26`，目标固定为
   `9ca6778f315e4d702af704be3c0bad2de3d2e8bb`；
2. 接受 Gate 10A-Prep `CONDITIONAL PASS`，从
   `cdf0d3a5e60e679b483e1ea89b046958e4877c22` 启动 R1。

## R1 范围

- `G10A-CI-P2-001`：GitHub Action Node 24 与生命周期；
- `G10A-DEP-P2-001`：Maven、pnpm、Flutter Pub、Kotlin/Gradle 同窗依赖快照；
- `G10A-SUP-P2-001`：单一 Action 版本账、活动/历史工作流治理与供应链回归。

依赖处理顺序固定为 Action、Maven、pnpm、Flutter Pub、Kotlin/Gradle。应用依赖和锁文件
默认不变；本批只在干净执行器生成候选、漏洞、许可证、兼容与回退证据，不以“存在升级”
自动授权大版本升级。

## 停止线

- 不新增业务、Requirement、Controller、Owner 状态机或外部适配器；
- 不改变资金、库存、租户、支付、同步和迁移语义；
- 不修改已发布迁移，不降低测试、安全、SBOM 或许可证阈值；
- 外部四项、UAT/REL、LIC/JSH 及全部零执行边界保持不变；
- 三项 Finding 只能达到 `VERIFIED_AWAITING_SPONSOR_CONFIRMATION`，未经确认不得关闭或进入 R2。

## 回退

Action 回退使用版本账中逐项冻结的原 SHA，并从全新提交完整复跑，不重跑失败 Job。
应用依赖未发生变更，因此其回退基线就是 Gate 10A-Prep 封存提交中的原 manifest/lockfile。
