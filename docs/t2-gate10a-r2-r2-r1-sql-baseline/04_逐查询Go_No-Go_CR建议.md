# 逐查询 Go / No-Go / CR 建议

## 100k 固定分布结果

| Query ID | 返回行 | 估算最大行 | 实际计划最大行 | 全表扫描 | Filesort | 建议 |
|---|---:|---:|---:|---|---|---|
| INV-FEFO | 100 | 4,476 | 100 | 否 | 是 | NO_GO_RUNTIME_PLAN_REVIEW_REQUIRED |
| INV-EXPIRY | 500 | 39,304 | 20,000 | 否 | 是 | NO_GO_RUNTIME_PLAN_REVIEW_REQUIRED |
| INV-PACKAGE | 12,000 | 28,094 | 12,000 | 否 | 是 | NO_GO_RUNTIME_PLAN_REVIEW_REQUIRED |
| PRM-RULES | 20,000 | 49,163 | 80,000 | 否 | 是 | NO_GO_RUNTIME_PLAN_REVIEW_REQUIRED |
| PRM-QUOTE-LINES | 400 | 400 | 400 | 否 | 是 | NO_GO_RUNTIME_PLAN_REVIEW_REQUIRED |
| RPT-SALES | 48,000 | 98,716 | 100,000 | 是 | 是 | CR_REQUIRED_BEFORE_PAGINATION_OR_RESPONSE_CHANGE |
| RPT-INVENTORY | 48,000 | 98,280 | 100,000 | 是 | 是 | CR_REQUIRED_BEFORE_PAGINATION_OR_RESPONSE_CHANGE |
| RPT-PAY-REC | 48,000 | 48,713 | 80,000 | 否 | 是 | CR_REQUIRED_BEFORE_PAGINATION_OR_RESPONSE_CHANGE |
| PAY-FACTS | 5,160 | 49,509 | 80,000 | 是 | 是 | NO_GO_RUNTIME_PLAN_REVIEW_REQUIRED |
| PUR-LINES | 400 | 400 | 400 | 否 | 是 | NO_GO_RUNTIME_PLAN_REVIEW_REQUIRED |
| TRF-LINES | 400 | 400 | 400 | 否 | 是 | NO_GO_RUNTIME_PLAN_REVIEW_REQUIRED |
| MBR-POINTS-FEFO | 16,000 | 47,173 | 80,000 | 否 | 是 | NO_GO_RUNTIME_PLAN_REVIEW_REQUIRED |

## 分组决策

### 需兼容性 CR

RPT-SALES、RPT-INVENTORY、RPT-PAY-REC 的返回规模需要评估分页、流式导出或响应形态调整。
这些变化可能影响 300 API 当前契约和 Vue 客户端，因此在正式整改前必须提交兼容性 CR、
冻结旧客户端窗口、错误码、幂等和回退方案。

### 需运行时计划复核

- PAY-FACTS：UNION 分支及其 501 次对账旅程需联合评估批量读取和索引候选。
- INV-EXPIRY：与 501 次批次临期旅程一起，在 Inventory/Catalog 正式端口内设计批量读取。
- 其余 7 条：比较当前计划与候选 SQL/索引，核验写放大、容量、租户权限和前向迁移影响。

### 查询数红基线

| 旅程 | JDBC 查询数 | 观察行数 | 结论 |
|---|---:|---:|---|
| REPORT_EXPORT_50_STORES_3_TYPES | 150 | 240,000 | NO_GO_LINEAR_QUERY_AMPLIFICATION |
| PAYMENT_RECONCILIATION_500_REFERENCES | 501 | 5,660 | NO_GO_LINEAR_QUERY_AMPLIFICATION |
| LOT_EXPIRY_500_CANDIDATES | 501 | 500 | NO_GO_LINEAR_QUERY_AMPLIFICATION |

## 决策边界

- 本报告不授权运行时修改。
- 不得因为观察到 filesort 就机械增加索引。
- 涉及 API 响应、Owner 边界、资金/库存/租户/同步语义或前向迁移时，必须独立 CR。
- 所有候选整改必须先有失败测试、计划对比、查询数上限、租户攻击和回退证据。
