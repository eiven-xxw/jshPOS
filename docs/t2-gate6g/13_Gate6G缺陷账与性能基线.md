# Gate 6G 缺陷账与性能基线

## 缺陷账

| 等级 | 开放数 | 结论 |
| --- | ---: | --- |
| P0 | 0 | 满足内部候选门槛 |
| P1 | 0 | 满足内部候选门槛 |
| P2 | 1 | 成本 Owner 历史 `/api/inventory` 路径保留兼容，Gate 6H 设计版本别名和弃用期 |

外部 `BLOCKED/DEFERRED` 不是内部缺陷关闭项，也不得写入 P0/P1 空账伪装为已解决。

### 已关闭门禁缺陷

| 缺陷 ID | 首次证据 | 影响 | 修复及本地回归 | 状态 |
| --- | --- | --- | --- | --- |
| S17-CI-001 | GitHub run `32451615660` / `pos-linux` | Gate 6E 退货退款正式运行码行覆盖率 `88.05% < 90%` | 补齐搜索、改量、提交、UNKNOWN 恢复、权限拒绝与无引用失败关闭测试；同口径 `349/385=90.65%`，136 项 Flutter 测试通过 | run `32456191093` 的 pos-linux 全量覆盖率门禁确认关闭 |
| S17-CI-002 | GitHub run `32451615660` / `runtime-stack-smoke` | RuoYi 基线 SQL 的中文用户昵称被 MySQL 客户端默认字符集错误解读，在 `sys_user.nick_name` 导入失败 | 不放宽列长；运行栈导入命令显式使用 `--default-character-set=utf8mb4` | run `32456191093` 的 MySQL 8.4 正式运行栈确认关闭 |
| S17-CI-003 | GitHub run `32452393104` / `runtime-stack-smoke` | 正式 JAR 启动执行 Flyway V202608160004 时，RuoYi 空环境 `sys_menu` 缺少权限契约已经使用的 `route_name` | 不修改任何已发布 Flyway；在 RuoYi 空环境脚本的旧位置参数菜单种子全部完成后前向增加 `route_name`，并新增机器审计固定顺序 | run `32456191093` 完整 52 条成功 Flyway 历史确认关闭 |
| S17-CI-004 | GitHub run `32453174850` / `runtime-stack-smoke` | 正式 JAR 按生产配置对 Redis 执行认证，但 CI Redis 未配置密码，Redisson 失败关闭 | 为合成 Redis 显式启用独立密码，应用通过命令行注入同值并由DAT机器审计核对两端一致；未关闭缓存或认证 | run `32456191093` 的 Redis 7.4 + Redisson 正式连接确认关闭 |
| S17-CI-005 | GitHub run `32453870001` / `runtime-stack-smoke` | 四类 ULID 生成器同时保留生产与测试构造器但未显式选择注入入口，Spring 正式装配在 `UlidGenerator` 失败关闭 | Order、Sync、Resilience、Release 生成器以 `@Autowired` 标记唯一生产构造器；机器审计拒绝任何同类歧义组件 | run `32456191093` 完整 Maven 与正式 JAR Bean 装配确认关闭 |
| S17-CI-006 | GitHub run `32454849799` / `internal-v1-core-candidate` | 正式 SQLite 已前向迁移至 V8，但候选证据采集器仍按旧 V7 测试展示标题匹配，实际双平台成功结果被误判为缺失 | 匹配稳定语义“保留签名包与成交分摊Schema”，仍要求 Linux/Windows 成功测试事件，不按版本标题绕过 | run `32456191093` 候选与证据索引 Job 确认关闭 |

## 性能基线

Gate 6G 仅冻结 CI 趋势上限：Server 回归 900 秒、MySQL 迁移 300 秒、Flutter 每平台 300 秒、Web 构建与测试 300 秒。候选报告记录 JUnit 聚合时间，GitHub run 记录真实 Job 时长。以上仅用于发现显著退化，不是终端性能、生产容量或商业 SLA。

最终 run `32456191093`：Server 455 秒、MySQL 109 秒、POS Linux 299 秒、POS Windows 172 秒、Web 69 秒；均在内部趋势上限内。候选报告记录 Server 必需套件聚合 26.287 秒、MySQL 聚合 31.565 秒。上述时间受 GitHub 执行器与缓存影响，只用于回归趋势。
