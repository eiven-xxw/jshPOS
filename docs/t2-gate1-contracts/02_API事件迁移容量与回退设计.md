# Gate 1 候选 API、事件、迁移、容量与回退

## API 与事件

候选 API 见 `contracts/t2/gate1/openapi-product-price-draft.yaml`。所有 POST/PUT 命令必须携带 `Idempotency-Key`，更新命令携带 `expectedVersion`；响应使用平台 `R<T>` 信封但不暴露 `tenant_id`。候选事件使用 `eventId/occurredAt/aggregateId/aggregateVersion/correlationId/data` 信封，消费者以 `eventId` Inbox 去重。

## Flyway 草案（不得在本阶段创建 SQL）

建议迁移批次：

1. Expand：新增 product、barcode、unit、assortment、import_batch、price_book、price_item、publication、data_package_manifest 表及 tenant 复合唯一/FK。
2. Backfill：仅合成/脱敏数据，逐租户游标执行并记录数量/hash；不得关闭租户拦截器批量写入。
3. Dual-read 验证：旧客户端 N-1 与新 Schema N 一致性、价格优先级与包摘要对账。
4. Contract：在兼容窗口和回退点过期且审批后，才删除旧列/索引；已发布脚本永不改写。

约束必须由应用和数据库双层实施：tenant 复合外键、SKU/条码/版本唯一、`CHECK(quantity > 0)`、金额 BIGINT 非负、价格有效期合法、发布内容不可变、数据包 hash/Schema/version 完整。

## 容量基线

- 小店 10k SKU、中店 100k SKU；每 SKU 平均 1.3 条码、1.1 单位、最多 50 个门店价覆盖。
- 100k 全量包解压后候选上限 256 MiB、清单 10k chunks 上限；最终阈值须由 Gate 1 基准实测冻结。
- 导入以 1k 行 chunk 预检，单批最大 100k；错误明细分页，禁止把全部错误装入 JVM 内存。
- 查询必须以 `(tenant_id, code/barcode/status)`、`(tenant_id, store_id, sku_id, effective_at)` 为前导索引，并用真实执行计划验收。

## 回退

- 应用回退不得回退数据库事实；失败迁移采用前向修复。
- 发布失败不改变上一 ACTIVE 价格版本和终端包；激活采用 staging + hash/signature + 原子指针切换。
- 导入回退是补偿命令并生成审计/事件，不执行物理 DELETE；已被后续版本或外部引用的行标记冲突等待人工处理。
