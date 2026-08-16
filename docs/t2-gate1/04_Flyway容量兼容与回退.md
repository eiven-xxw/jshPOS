# Gate 1 Flyway、容量、兼容与回退

## 1. 迁移序列

- `V202608160003` Expand：新增 `cat_*`、`prc_*` 和 `dpk_catalog_package` 表、租户复合键、检查约束和必要触发器；
- `V202608160004` 权限：只增加 Gate 1 菜单权限，固定高位 ID 并在冲突时阻断；
- 已发布 Gate 0 的 `V202608160001/002` 摘要继续锁定，绝不改写；
- 本 Gate 不执行 Contract 删除；失败迁移只通过新版本前向修复。

## 2. 容量与索引

| 场景 | 目标 | 强制策略 |
|---|---:|---|
| 小店目录 | 10k SKU | `(tenant_id, sku_code/status)`、`(tenant_id, barcode)` 前导索引 |
| 中型目录 | 100k SKU | 本 Gate 提供 100k canonical 预检算法与 staging 上限验证；流式上传、分批落库和错误分页保留为进入试点前的性能加固项 |
| 价格解析 | 单店 100k SKU | `(tenant_id, scope_type, store_id, sku_id, unit_id, effective_from)` |
| 数据包 | 100k SKU | 确定性 canonical hash、摘要和损坏拒收；流式编码与 256 MiB 内存告警线保留为进入试点前的性能加固项 |

10k/100k 测试记录行数、耗时和最大暂存集合；绝不以本地/CI 数字承诺生产吞吐。真实执行计划、真实数据分布和锁时长在试点前另行验收。

## 3. 回退与兼容

- 应用可回退，但数据库 Schema 不反向回滚；新列保持旧版本可忽略，N/N-1 窗口内只做 expand；
- 导入发布通过活动版本指针切换；失败删除 staging 临时对象或标记失败，不改活动版本；已发布回退创建补偿版本；
- 价格发布失败保持上一 PUBLISHED 版本；当前 Gate 的安全回退是将错误版本置为 RETIRED，并由解析器回落到仍有效的上一发布版本；
- 数据包在摘要、签名或对象写入失败时不登记 AVAILABLE；终端拿到损坏或不兼容包必须继续使用旧包；
- 迁移校验包括首次 migrate、第二次 0 migration、validate、表/索引/权限、跨租户 FK、发布后不可变触发器。
