# Gate 0 Flyway、容量、兼容与回退设计

## 1. 版本与目录

Gate 0 服务端迁移使用 `server/ruoyi-modules/jshpos-foundation/src/main/resources/db/migration`，仓库级版本为 `V202608160001__gate0_foundation.sql`。Flyway history 表使用 `jshpos_flyway_schema_history`，对既有 RuoYi 非空库执行显式 baseline version `0` 后迁移；禁止修改 RuoYi 上游建库脚本冒充迁移。

已进入共享分支或产生 CI checksum 证据的迁移永久只读。修复使用更高版本的前向迁移，checksum 清单由 `contracts/t2/gate0/migration-checksums.json` 固定。

## 2. Expand 设计

首迁移只新增：

- `jsh_org_unit`
- `jsh_store`
- `jsh_staff_scope`
- `jsh_config_template`
- `jsh_config_template_version`
- `jsh_config_binding`
- `jsh_audit_event`

不修改 `sys_*` 表，不创建订单、支付、库存、促销、商品或价格表。外键均包含可验证的租户所有权：通过复合唯一键与复合外键防止跨租户引用；应用层检查是第二道防线，不替代数据库约束。

## 3. 锁与容量

- 新表对既有表无 DDL 锁；迁移目标为 MySQL 8.0，字符集 `utf8mb4`，时间点 `datetime(6)` UTC。
- 高频查询索引以 `(tenant_id, ...)` 开头；审计索引为 `(tenant_id, occurred_at, audit_id)`。
- 配置 JSON 上限 64 KiB，审计脱敏摘要上限 8 KiB；API 和数据库双重校验。
- 10M 审计行时在线查询必须按 tenant+time keyset 分页，禁止无界 offset 导出；真实压测在后续性能门单独执行。

## 4. 兼容与部署顺序

1. 备份并确认恢复点；验证 Flyway validate。
2. 先部署 Expand 迁移；旧应用不引用新表，保持兼容。
3. 部署 Gate 0 应用；健康检查确认 module/migration。
4. 灰度启用管理菜单和权限；观察拒绝/错误/审计指标。
5. Gate 0 不执行 Contract DDL。

## 5. 失败与回退

- 迁移未提交：Flyway 失败并停止部署，修正原因后重跑；不得手工标绿。
- MySQL DDL 已部分提交：停止应用发布，执行已评审的更高版本前向修复；禁止在生产自动 drop。
- 应用健康失败但迁移成功：回退应用到基线兼容版本；新表保留且不被旧应用读取。
- 配置误发布：应用级 binding 回退到 previous published version，保留所有版本与审计。
- 租户泄漏或权限 P0：立即关闭 Gate 0 功能入口、回退应用、保全审计和关联日志，启动安全事件响应。

## 6. 迁移测试

CI 必须覆盖空 MySQL 迁移、重复 migrate 无新增、既有非空 schema baseline、checksum validate、复合外键跨租户拒绝、唯一约束、审计 UPDATE/DELETE 拒绝和应用版本回退兼容。SQLite 不属于 Gate 0，不创建任何正式 POS 迁移。
