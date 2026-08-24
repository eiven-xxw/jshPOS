# CR-T2G8C-013：PERF-002 首次候选 MySQL 前向兼容失败

## 事实

候选提交 `f7abda8f97e8755761cf47ea0ab8ebeb17f44cb0` 的 GitHub Run `32701100510` 已通过治理双平台、Web、Flutter 双平台/Android 与 POS 性能作业。Owner 容量作业在正式 MySQL 8.4 执行 `DailyCloseMySqlIT`、`ExceptionCenterMySqlIT` 时失败：两项历史测试均把 Flyway 当前版本固定断言为 `202608230074`，而后续已发布的 SaaS、Subscription、Service 前向迁移已将完整当前 Schema 推进至 `202608230079`。

## 根因与处置

这是测试的前向兼容断言过时，不是迁移校验、表约束、金额数量守恒或容量断言失败。只将两项测试改为“当前版本不得早于所属 Owner 的 V74 基线”，继续在完整当前 Schema 上逐表、逐约束及百万级合成趋势验证。

## 边界

- 失败 Run 与原始注解永久保留，不执行失败 Job 重跑；
- 不修改、新增或重排任何已发布迁移；生产运行时代码变化为 0；
- 不跳过测试，不降低性能、安全、覆盖率、SBOM、许可证或证据门禁；
- 修复提交必须触发一次全新的完整工作流；`T2-PERF-002` 继续为 `VERIFIED` 候选，`T2-RDY-001` 继续 `DRAFT`。
