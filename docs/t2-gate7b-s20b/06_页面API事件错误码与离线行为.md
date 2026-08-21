# 页面、API、事件、错误码与离线行为

## 1. Flutter 页面旅程

### 换货

原单查询 → 选择可退行/数量 → 扫描新商品 → 只读预检 → 主管批准 →
执行原单退货退款 → 权威完成后创建新销售 → 收取新销售款 → 展示两张凭证和换货关联。

页面必须显示每条腿的独立金额/状态、展示差额、原命令关联 ID、断网/UNKNOWN 和
人工修复入口。防重复点击只复用原幂等键。V1 离线不能新建换货，返回
`EXCHANGE_ONLINE_REQUIRED`；已创建换货可查看最后缓存状态，联网后以原 ID 查询。

### 组合支付

确认应收 → 选择 2—8 个份额 → 校验并冻结计划 → 顺序收取电子份额 → 最后收现金 →
全部权威成功后完成订单。任何 UNKNOWN 时锁定编辑、取消和后续份额，只开放查询/恢复。
电子入口在 `T2-PAY-002` 未解阻时明确显示“外部支付未开通”，不能展示成功。

## 2. API 与事件

OpenAPI：`contracts/t2/gate7b-s20b/openapi-pos-second-batch-v1.yaml`。

事件 Schema：

- `exchange-orchestration-events-v1.schema.json`：换货状态、两条命令、摘要和恢复观察；
- `tender-plan-events-v1.schema.json`：计划、份额状态、金额守恒和资金观察。

事件必须包含 event ID、aggregate ID、tenant/store、Schema 版本、sequence、payload hash、
correlation/causation ID；重复幂等、同键异内容隔离、乱序等待缺口，禁止静默丢弃。

## 3. 错误码

| 错误码 | 含义与客户端动作 |
| --- | --- |
| `EXCHANGE_ONLINE_REQUIRED` | 离线不准新建换货；保留购物选择并等待联网 |
| `EXCHANGE_PREVIEW_STALE` | 报价/上限已变化；重新预检生成新草稿，不覆盖已批准事实 |
| `EXCHANGE_RETURN_UNKNOWN` | 原退货/退款未知；只查询原命令 |
| `EXCHANGE_MANUAL_RECOVERY_REQUIRED` | 部分成功；受权人员进入恢复工作台 |
| `TENDER_SUM_MISMATCH` | 份额总额不等于订单应收；不得冻结 |
| `TENDER_SEQUENCE_BLOCKED` | 前一份额未终态；不得推进 |
| `PAYMENT_EXTERNAL_BLOCKED` | 电子外部边界未解阻；不得调用 Provider |
| `PAYMENT_UNKNOWN_QUERY_ONLY` | 资金未知；只允许查询/观察 |
| `IDEMPOTENCY_CONTENT_MISMATCH` | 同键异内容；隔离、告警和审计 |
| `TENANT_SCOPE_DENIED` | 可信租户/门店范围拒绝，不泄露资源存在性 |

## 4. 管理后台恢复工作台

本批只定义契约，不提前实现 Vue 页面。工作台须按租户/门店/状态/业务日查询，展示
Owner 观察链和原命令；操作仅允许“重新查询、推进已确认检查点、提交人工核对结果”，
禁止手工改成功状态、修改金额、删除事件或生成替代资金命令。
