# Gate 1 权限、审计、API 与事件

## 1. 权限矩阵

| 能力 | 查询 | 写入/审批 | 高风险审计 |
|---|---|---|---|
| 商品/分类/品牌/单位 | `catalog:product:query` | `catalog:product:manage` | 创建、修改、停用、恢复、条码/单位变化 |
| 商品导入 | `catalog:import:query` | `catalog:import:validate`、`catalog:import:commit`、`catalog:import:rollback` | 源摘要、行数、错误数、发布版本和回退冲突 |
| 价格 | `pricing:book:query` | `pricing:book:manage`、`pricing:book:approve`、`pricing:book:publish` | 金额、范围、时间、内容摘要和发布结果 |
| 数据包 | `catalog:package:query` | `catalog:package:build`、`catalog:package:publish` | tenant/store/version/hash/keyId/result |

UI 权限只控制展示。Controller 使用 Sa-Token 权限注解，应用服务再次校验可信租户、门店范围、状态和乐观版本。平台跨租户任务必须逐租户建立可信上下文并审计，禁止关闭租户拦截批量扫描。

## 2. 幂等、审计和错误

- 创建、更新、导入、审批、发布和构包命令携带 `Idempotency-Key`；服务端保存 tenant+operation+key+requestHash+resultRef；
- 同键不同 requestHash 返回 `CAT-IDEMPOTENCY-CONFLICT`；并发只允许一个业务效果；
- 审计只保存字段级差异摘要、计数、哈希和稳定 ID，不保存导入文件、完整条码清单、签名私钥或未脱敏报文；
- 主要错误：`CAT-TENANT-MISMATCH`、`CAT-VERSION-CONFLICT`、`CAT-BARCODE-DUPLICATE`、`CAT-UNIT-INVALID`、`CAT-IMPORT-INVALID`、`PRC-OVERLAP`、`PRC-STATE-INVALID`、`DPK-HASH-MISMATCH`、`DPK-SIGNATURE-INVALID`、`DPK-INCOMPATIBLE`。

## 3. API 与事件

正式 OpenAPI 从 Gate 0 DRAFT 契约升级为 `contracts/t2/gate1/openapi-product-price-v1.yaml`；响应保持平台 `R<T>`，不向客户端返回 tenant_id。事件使用版本化 JSON Schema：

- `catalog.product.changed.v1`：SKU 身份、状态、条码/单位版本摘要；
- `catalog.import.committed.v1`：批次、源摘要、成功/失败计数和发布版本；
- `pricing.price-book.published.v1`：价格簿、发布版本、生效时间和内容摘要；
- `catalog.data-package.available.v1`：store/version/schema/hash/keyId。

消费者以 eventId Inbox 去重；相同 eventId 不同 payloadHash 为安全阻断。事件只通知事实，不允许消费者反向覆盖本域表。
