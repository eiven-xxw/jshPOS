# Gate 3A 权限、审计、API、事件与 Provider 端口

## 1. 权限

| 权限 | 能力 |
|---|---|
| `payment:intent:create` | 对可见门店原单创建支付意图 |
| `payment:attempt:create` | 为非 UNKNOWN 意图创建一次明确 attempt |
| `payment:read` | 按数据范围查看支付和历史 |
| `refund:create` | 创建原单退款申请 |
| `refund:approve` | 审批并推进退款，不得与申请人相同 |
| `refund:read` | 查看退款和占额 |
| `reconciliation:run` | 导入受控账单源并执行匹配 |
| `reconciliation:manage` | 调查、解决、审批和关闭差异 |
| `reconciliation:read` | 查看差异和证据 |

接口层使用权限注解，应用层再次校验可信租户、门店数据范围、操作者和职责分离。内部 Provider 观察端口不暴露成 Controller。

## 2. 审计

创建意图/attempt、观察合并、UNKNOWN、late success、观察冲突、退款创建/审批/状态变化、账单入库、差异生成/解决/审批/关闭全部写不可变审计。审计保存 tenant、store、actor、approver、trace、command、before/after、amount/currency、request hash 和 reason；禁止密钥、付款码和敏感原文。

## 3. API 与事件

正式管理/应用 API 见 `contracts/t2/gate3a/openapi-payment-core-v1.yaml`。API 不包含 Provider 回调和网络调用。

事件：`payment.intent.created.v1`、`payment.attempt.created.v1`、`payment.status.changed.v1`、`refund.created.v1`、`refund.status.changed.v1`、`reconciliation.case.opened.v1`、`reconciliation.case.closed.v1`。事件与领域状态、审计在同一事务写 Outbox。

## 4. Provider 端口

`ProviderObservationPort` 只接收已经由未来适配器完成鉴权/验签的标准观察对象；Gate 3A 运行时不注册任何外部适配器。Fake 实现只能位于 test scope，并且测试证据标记 `FAKE_CONTRACT`。
