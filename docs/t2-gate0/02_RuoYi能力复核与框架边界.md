# RuoYi-Vue-Plus 能力复核与框架边界

## 1. 复核基线

复核对象为仓库内 RuoYi-Vue-Plus `5.6.2`：Sa-Token 登录会话、`TenantLineInnerInterceptor`、`TenantHelper`、租户 Redis/Cache 前缀、`sys_tenant/sys_dept/sys_user/sys_role`、数据权限拦截器、`@Log` 操作日志、MyBatis-Plus 乐观锁与 Actuator。

## 2. 复用能力

| 平台能力 | Gate 0 用法 | 约束 |
|---|---|---|
| Sa-Token/LoginHelper | 提供已认证 tenant/user/dept/permission | 只读适配；请求参数不得覆盖会话身份 |
| MyBatis 租户拦截 | 为所有非排除 `jsh_*` 表追加 `tenant_id` | 业务 Mapper 再加“缺失租户失败关闭”切面 |
| `TenantEntity` | 统一 `tenant_id VARCHAR(20)` 映射 | 不接受 Controller 赋值；应用服务创建时从可信上下文写入 |
| `sys_dept` | 继续承担平台部门树和 RuoYi 数据权限 | 业务组织/门店有独立 Owner，可选引用 `sys_dept`，不扩字段污染平台表 |
| `sys_user/sys_role/menu` | 员工账户、角色、功能权限 | 业务门店范围使用独立 `jsh_staff_scope` 扩展 |
| `@SaCheckPermission` | Controller 入口的服务端功能权限 | 应用服务仍执行对象/门店范围授权 |
| `@Log`/`sys_oper_log` | 平台操作日志和运维检索 | 不能替代追加式领域审计 `jsh_audit_event` |
| 乐观锁 | Gate 0 可变聚合并发控制 | 更新必须带 version，冲突不得最后写入覆盖 |
| Actuator/Micrometer | 健康和低基数指标 | 禁止把 user、订单、手机号等高基数/敏感值作为 tag |

## 3. 必须扩展

1. `TrustedPrincipalSource` 适配 Sa-Token；`TrustedTenantContext` 在租户/操作者缺失时失败关闭。
2. 对 `com.jingshanghui.pos.foundation.infrastructure.persistence.mapper` 的每次调用执行严格租户前置检查，补足上游“无租户时忽略租户条件”的风险。
3. `jsh_org_unit/jsh_store` 维护业务组织、门店、时区和业务日；它们不复制 RuoYi 登录/角色。
4. `jsh_staff_scope` 将 RuoYi 员工映射到租户、组织子树或门店数据范围。
5. `jsh_config_template/jsh_config_template_version/jsh_config_binding` 管理三业态模板的版本、发布、激活和回退。
6. `jsh_audit_event` 只追加，记录 tenant、actor、correlation、action、target、before/after 摘要和结果。
7. HTTP 关联标识过滤器、低基数指标与租户安全资源键构造器统一放在 foundation 适配层。

## 4. 禁止修改

- 不向 `sys_tenant/sys_dept/sys_user/sys_role` 增加鲸熵汇领域字段；
- 不改变 RuoYi 登录、密码、Token、菜单和角色的核心语义；
- 不在 `ruoyi-common` 工具类中加入业务状态、行业或门店规则；
- 不在 Controller 拼装 SQL、计算业务日、决定配置状态或写审计摘要；
- 不把租户、业务组织、行业模板硬编码进通用租户排除表；
- 不通过 `TenantHelper.ignore` 访问 `jsh_*` 业务表；跨租户管理只能采用单租户循环、显式内部身份和独立审计。

## 5. 上游升级策略

`jshpos-foundation` 通过 Maven 依赖和 Spring AutoConfiguration 接入，减少对上游目录的 patch。上游升级先运行边界、租户攻击、权限和迁移回归；若上游租户行为改变，保持失败关闭并通过 ADR 决定适配，不直接删除本模块防线。
