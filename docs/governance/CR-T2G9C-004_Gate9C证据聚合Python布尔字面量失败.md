# CR-T2G9C-004 Gate 9C 证据聚合 Python 布尔字面量失败

## 1. 事实

Gate 9C 首个候选 `b7c18348d9de496ad9abb685cd46d3715365eaf6` 的 GitHub Run
`32926660201` 中，Ubuntu/Windows 治理、范围、Server、Web 与 Flutter 双平台七个上游
作业全部通过；最后证据聚合作业执行 `build_t2_gate9c_seal_evidence.py` 时，把 Python
布尔值写成 JSON 字面量 `false`，触发 `NameError` 并失败关闭。

## 2. 决策

- 仅将 Python 字面量 `false` 修正为 `False`；
- 把失败 Run、候选提交、失败分类和未重跑事实追加到失败历史；
- 不重跑失败 Job，从新提交完整运行 Gate 9C CI；
- 不修改运行时、业务、依赖、数据库、迁移、资金、库存或租户语义。

## 3. 验收

本地以七个非空模拟生产者目录执行证据索引构建，确认输出中的 `automaticTag` 为 JSON
布尔 `false`；新提交完整 Gate 9C CI 必须全绿，项目发起人确认前仍不得创建 tag。
