# Gate 4C 权限、审计、API、事件与跨模块契约

## 1. 权限与数据范围

| 权限 | 用途 |
|---|---|
| `inventory:cost:read` | 按已授权门店仓查询成本余额与流水 |
| `inventory:cost:policy` | 租户管理员发布未来生效的成本策略 |
| `inventory:cost:rebuild` | 租户管理员发起受控投影重建 |

服务端在 Mapper 前执行可信租户和门店范围授权。路由权限仅控制展示；任务、缓存、导出和对象存储同样使用可信租户命名空间。

## 2. 审计

策略发布、成本来源应用、估计成本、负库存结清差异、零数量结清、重建验证/切换均记录操作者、来源库存流水、关联标识、前后数量/金额、摘要、策略版本、原因和 UTC 时间。审计只追加。

## 3. API

OpenAPI `contracts/t2/gate4c/openapi-costing-v1.yaml` 冻结策略发布、余额/流水查询和重建。不存在“外部提交成本流水”端点；库存 Owner 只能调用进程内端口。

错误码至少区分：`CST-SOURCE-MISSING`、`CST-IDEM-CONFLICT`、`CST-SEQUENCE-GAP`、`CST-LATE-REQUIRES-REBUILD`、`CST-CURRENCY-MISMATCH`、`CST-COST-MISSING`、`CST-RETURN-QTY-INSUFFICIENT`、`CST-VERSION-CONFLICT` 和 `TENANT_CONTEXT_MISMATCH`。

## 4. 事件

`inventory.cost.changed.v1` 保存成本流水 ID、仓库、SKU、库存流水 ID/序号、移动类型、精确数量、单位成本、成本金额、平均成本、估计标识、差异、策略版本和关联标识。所有 Decimal 使用字符串；事件与成本流水同事务写 Outbox。

## 5. 跨模块契约

- `AuthoritativeCostPostingPort`：由库存 Owner 调用，不含 tenant_id；一次调用只描述已插入的库存流水。
- `ProcurementCostSourcePort`：只读已确认采购事实；收货提供冻结单价/换算，退货提供原收货行关系。未确认或跨租户返回不可见。
- `T2-TRF-001` 的设计 Schema 继续 `x-runtime-allowed=false`；未来调拨成本必须继承发出成本，不得按目的仓当前平均伪造。

