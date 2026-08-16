# Gate 1 Flyway、容量、兼容与回退

## 1. 迁移序列

- `V202608160003` Expand：新增 `prd_*`、`prc_*` 和 `sync_data_package` 表、租户复合键、检查约束和必要触发器；
- `V202608160004` 权限：只增加 Gate 1 菜单权限，固定高位 ID 并在冲突时阻断；
- 已发布 Gate 0 的 `V202608160001/002` 摘要继续锁定，绝不改写；
- 本 Gate 不执行 Contract 删除；失败迁移只通过新版本前向修复。

## 2. 容量与索引

| 场景 | 目标 | 强制策略 |
|---|---:|---|
| 小店目录 | 10k SKU | `(tenant_id, sku_code/status)`、`(tenant_id, barcode)` 前导索引 |
| 中型目录 | 100k SKU | 1k 行流式校验/分批 staging，错误分页，禁止整文件进入 JVM |
| 价格解析 | 单店 100k SKU | `(tenant_id, scope_type, store_id, sku_id, unit_id, effective_from)` |
| 数据包 | 100k SKU | 流式 canonical hash；服务端内存峰值门禁，256 MiB 为候选告警线而非商用 SLA |

10k/100k 测试记录行数、耗时和最大暂存集合；绝不以本地/CI 数字承诺生产吞吐。真实执行计划、真实数据分布和锁时长在试点前另行验收。

## 3. 回退与兼容

- 应用可回退，但数据库 Schema 不反向回滚；新列保持旧版本可忽略，N/N-1 窗口内只做 expand；
- 导入发布通过活动版本指针切换；失败删除 staging 临时对象或标记失败，不改活动版本；已发布回退创建补偿版本；
- 价格发布失败保持上一 PUBLISHED 版本，回退通过新的发布记录恢复旧内容摘要；
- 数据包 BUILDING/SEALED 失败不进入 AVAILABLE；终端拿到损坏或不兼容包必须继续使用旧包；
- 迁移校验包括首次 migrate、第二次 0 migration、validate、表/索引/权限、跨租户 FK、发布后不可变触发器。
