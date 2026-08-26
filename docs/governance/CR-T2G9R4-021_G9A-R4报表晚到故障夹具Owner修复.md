# CR-T2G9R4-021：G9A-R4 报表晚到故障夹具 Owner 修复

## 1. 触发证据

- 候选提交：`5508d3400093758e97100d1c890a5b7f085a8c6b`
- GitHub Actions Run：`32915857227`
- 失败 Job：`formal-runtime`（`98019973476`）
- postflight：三业态、22 Owner、66 检查点、36 项守恒全部通过
- 失败位置：`r4-late-sequence-2`
- 服务端结果：HTTP `200`、业务码 `400`、`RPT-G5D-005: 来源 Owner 与指标族不匹配`

## 2. 已确认根因

R4-F09 需要验证 Reporting 对序号 2 先到、序号 1 晚到的显式缺口与最终收敛。夹具为隔离
该序列而虚构了 `R4FAULT` 来源 Owner，但 Reporting Owner 的既有白名单只接受权威
`ORDER/SHIFT/PROMOTION` 销售来源及 `INVENTORY/COSTING` 库存成本来源。服务端拒绝
虚构 Owner 符合已接受规则。

## 3. 最小修复

1. 先增加禁止 `R4FAULT`、要求合法 `ORDER` 及独立 `R4-F09` 分区的可执行失败回归；
2. F09 使用合法 `ORDER` Owner 和 SALES 指标族；
3. 两条事件使用同一故障专用分区 `ORDER:<store>:<businessDate>:R4-F09`，与 postflight
   已存在的正式 ORDER 序列隔离，再按 2→1→2 重放验证缺口、追平和幂等；
4. 不修改 Reporting Java、Owner 白名单、API、Schema、投影规则、数据表或迁移；
5. 不重跑失败 Job，新提交从头运行完整 G9A-R4 CI。

## 4. 状态与边界

- `G9A-E2E-P1-001` 继续 `OPEN`；
- V87 仍是相对 G9A-R4 基线唯一允许新增的 Flyway 前向迁移；
- 外部 BLOCKED、UAT/REL DRAFT、LIC/JSH DEFERRED 与零执行边界不变；
- 完整 CI 通过且经项目发起人确认前，不关闭 Finding，不启动完整 Alpha 或生产发布。
