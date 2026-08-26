# CR-T2G9R4-022：G9A-R4 故障证据 SeedId 规范化修复

## 1. 触发证据

- 候选提交：`52d87dab8f7339d93808169b49aff0b908dab146`
- GitHub Actions Run：`32916495097`
- 失败 Job：`formal-runtime`（`98021818625`）
- postflight：三业态、22 Owner、66 检查点、36 项守恒全部通过
- 失败信息：`dict() got multiple values for keyword argument 'seedId'`

失败发生在代码已经核对 12 个固定 seed 全部存在且 `pass=true` 之后、写最终 JSON 证据之前。
因此这不是业务、故障注入或守恒失败，而是证据序列化对 F07 已携带 `seedId` 的重复处理。

## 2. 已确认根因

F07 来自 Flutter 脱敏证据，行内已经包含 `seedId=R4-F07`。Python 汇总器又用
`dict(seedId=seed_id, **row)` 以关键字参数注入同名字段；Python 对重复关键字正确抛出
`TypeError`，使已经完成的故障矩阵不能落盘。

## 3. 最小修复

1. 先增加禁止重复关键字构造、要求规范化构造的可执行失败回归；
2. 改为 `dict(row, seedId=seed_id)`，保留行内证据并以固定总账键覆盖/确认最终 `seedId`；
3. 不修改 12 个 seed 的业务动作、判定、顺序、运行时、API、数据库或迁移；
4. 不重跑失败 Job，新提交从头运行完整 G9A-R4 CI。

## 4. 状态与边界

- `G9A-E2E-P1-001` 继续 `OPEN`；
- V87 仍是相对 G9A-R4 基线唯一允许新增的 Flyway 前向迁移；
- 外部 BLOCKED、UAT/REL DRAFT、LIC/JSH DEFERRED 与零执行边界不变；
- 完整 CI 通过且经项目发起人确认前，不关闭 Finding，不启动完整 Alpha 或生产发布。
