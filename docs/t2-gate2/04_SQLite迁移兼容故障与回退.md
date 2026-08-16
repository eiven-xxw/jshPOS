# Gate 2 SQLite、Flyway、兼容、故障与回退

## 1. SQLite Schema

正式表：`local_device_binding`、`local_shift`、`local_order`、`local_order_line`、`local_order_state_history`、`local_cash_payment`、`local_cash_ledger`、`local_print_job`、`local_outbox`、`local_idempotency`、`local_audit_event`、`local_schema_history`。

强制约束：

- 所有事实表显式含 `tenant_id/store_id/terminal_id`，并通过复合外键防止跨租户拼接；
- ULID 为 26 位大写 ASCII，货币仅 `CNY`，金额非负或按现金流水类型明确正负；
- 数量为最多 6 位小数的规范文本，并由应用精确解析；
- 同活动终端/员工班次、订单本地单号、命令幂等键、Outbox 事件 ID 均有唯一约束；
- 事实不使用物理删除；Outbox `ACKED` 清理属于 S3 设计，不在 S2 执行。

数据库连接初始化：`journal_mode=WAL`、`foreign_keys=ON`、`synchronous=FULL`（测试和正式现金路径默认）、`busy_timeout=5000`、启动 `quick_check`。运行前检查可写性；存储不足映射为稳定错误并禁止新增成交。

## 2. Schema 版本

- V1 创建绑定、班次、订单、现金、Outbox、幂等和审计表；
- `PRAGMA user_version=1` 与 `local_schema_history` checksum 必须一致；
- 迁移在独占启动阶段执行，每个版本一个事务，重复启动不得改变 Schema；
- 已发布 SQL/checksum 不允许修改，只能添加前向修复版本；
- App 兼容窗口为 Schema N/N-1；一旦 V1 提交，应用回退版本必须声明支持 V1，不执行破坏性 down migration。

## 3. 服务端 Flyway

Gate 2 新增全局唯一版本：

- `V202608160005__gate2_order_shift_cash.sql`：订单、班次、现金、幂等、Outbox、审计及租户复合约束；
- `V202608160006__gate2_order_permissions.sql`：Gate 2 权限点；
- 迁移 checksum 单独封存，MySQL 8.4 clean container 执行 migrate→repeat migrate→validate；
- 已发布 Gate 0/1 迁移 checksum 必须原样通过。

## 4. 原子事务写入顺序

1. 验证可信设备绑定、OPEN 班次和权限；
2. 查询幂等记录；同键同 hash 直接返回；
3. 插入/冻结订单与行；
4. 插入完整状态历史；
5. 插入现金 Payment 与现金流水；
6. 插入打印任务（只排队，不调用硬件）；
7. 插入 Outbox 事件；
8. 插入审计和幂等结果；
9. 提交。

任一步异常必须回滚。测试在 2—8 每个边界注入失败，事务后九类事实计数必须全部为 0，原有班次和历史事实保持不变。

## 5. 崩溃与恢复

| 故障 | 预期行为 |
|---|---|
| 事务提交前 kill | WAL 回滚；无订单/现金/Outbox/幂等残留 |
| COMMIT 返回前进程终止 | 重启后按幂等键查询权威结果；不得凭 UI 再建单 |
| 磁盘写入/空间不足 | 整体失败，返回存储错误，禁止继续成交 |
| `quick_check` 失败 | 进入 BLOCKED/恢复模式，只读导出证据，不尝试自动重建交易事实 |
| 外设/打印失败 | 交易事实保持成功，打印任务独立重试；本 Gate 只验证队列 |
| 网络不可用 | 不影响现金成交；Outbox 保持 PENDING；本 Gate 不尝试发送 |
| 业务日跨午夜 | 沿用 OPEN 班次冻结业务日 |

物理拔电、弱机 fsync 和 Android 文件系统行为必须在主认证设备解阻后形成 `REAL_DEVICE` 证据；桌面进程 kill 只属于 `INTEGRATION`。

## 6. 回退

- 应用灰度失败：停止扩张，保留数据库，回退到声明兼容当前 Schema 的 App；
- Schema 失败：事务内迁移自动回滚；已提交版本使用前向修复，不逆向删表/列；
- 交易缺陷：停止新交易并保留全部事实，使用经审批的补偿命令，禁止直接 SQL 改金额或删除订单；
- Gate No-Go：分支不合并、不打阶段完成 tag；不删除证据和失败 seed。

