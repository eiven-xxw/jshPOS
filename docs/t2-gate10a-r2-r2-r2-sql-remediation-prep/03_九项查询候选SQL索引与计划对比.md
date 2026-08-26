# 九项查询候选 SQL、索引与计划对比

所有内容均为候选；不存在“观察到 filesort 就建索引”的自动规则。后续必须在同一 MySQL 8.4.11、
相同 10k/100k 数据、相同参数和表统计下比较当前与候选计划，保存 `SHOW INDEX`、JSON/TREE、实际行、
查询数和写放大。未经确认不实施。

| 查询 | 当前观察 | 候选 SQL/端口 | 索引候选 | 必须守住的语义 |
|---|---|---|---|---|
| INV-FEFO | 100行，filesort | 有序 ID 限制后由 Owner 锁定复核 | 先验证既有 idx_inv_lot_fefo 与余额关联，不默认新增 | FEFO 顺序、FOR UPDATE、余额不足失败关闭 |
| INV-EXPIRY | 500行，20k实际行，501放大 | keyset 批次页 + 策略批量端口 | tenant/store/warehouse/expiry/lot | 门店时区、规则版本、过期判定 |
| INV-PACKAGE | 12k行，filesort | 确定性 keyset 分块与断点 | tenant/store/warehouse/sku/expiry/received/lot | 包顺序、摘要、跨租户拒绝 |
| PRM-RULES | 20k行，80k实际行 | Scope 一次批量读取或预聚合关联 | 规则生效范围 + Scope 组合索引 | 优先级、生效窗、解释链、金额守恒 |
| PRM-QUOTE-LINES | 400行，filesort | 既有唯一键已覆盖顺序时 KEEP_SQL | 既有 uk_prm_quote_line_no | 成交报价行顺序和身份 |
| PAY-FACTS | 5,160行，full scan，501放大 | 日范围集合读取 + 引用批量匹配 | attempt/intent/refund 三表候选 | UNKNOWN、成功单调、禁止二次资金命令 |
| PUR-LINES | 400行，filesort | 既有组合索引已覆盖时 KEEP_SQL | 既有 idx_pur_order_line_head | 采购单行顺序与冻结换算 |
| TRF-LINES | 400行，filesort | 既有组合索引已覆盖时 KEEP_SQL | 既有 idx_trf_line_head | 调拨数量和发出成本快照 |
| MBR-POINTS-FEFO | 16k行，filesort | 评估空到期拆分；规范排序键仅 CR 候选 | 先验证既有 idx_mbr_points_lot_fefo | 锁顺序、可用积分、到期规则 |

## 计划判定

- `KEEP_SQL`：当前查询在目标分布满足行数/查询数/权限预算，新增索引收益不足以抵消写放大。
- `SQL_CANDIDATE`：候选计划减少扫描或排序，且结果、锁、顺序、摘要逐字段等价。
- `INDEX_CANDIDATE`：MySQL 8.4 对比证明收益，写放大、存储、DDL 时间、回退和前向迁移已评估。
- `OWNER_BATCH_PORT_CANDIDATE`：线性调用必须在 Owner 内集合读取，不跨 Mapper、不开数据库后门。
- 任一金额、库存、支付、租户、同步、状态机或 API 语义漂移均为 `NO-GO + 独立 CR`。
