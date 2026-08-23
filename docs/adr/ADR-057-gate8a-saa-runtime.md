# ADR-057：Gate 8A SaaS 开户与权益运行时边界

- 状态：Accepted
- 日期：2026-08-23
- 范围：T2-SAA-001
- 起点：`bcfcaa4621ea55c61bd1cd22fc355b5f74d8dae4`

## 决策

1. 新建 `jshpos-saas` 模块，独占商户申请、套餐、权益版本、租户商业生命周期、配额、审计与 Outbox。
2. 租户创建前的操作必须具备独立的平台管理员权限；租户管理员不能替代该权限。
3. Foundation 定义 `TenantProvisioningPort` 并适配 RuoYi `ISysTenantService`；SaaS 不依赖 `ruoyi-system`，不直接写系统表。
4. 技术租户由服务端生成 `tenant_id`，初始为停用；只有初始化检查点完整后才可激活。
5. 套餐权益发布后内容不可变；服务端可信上下文负责授权与配额校验，菜单隐藏只负责体验。
6. 暂停、停用和逻辑注销不物理删除业务历史，并保留退款、查询、对账、审计、备份恢复、法定导出、迁移和删除请求能力。
7. `T2-SUB-001` 与 `T2-SVC-001` 继续 DRAFT；本 ADR 不准入真实收费、Provider、设备、伙伴、完整 Alpha 或生产执行。

## 后果

- RuoYi System 仍是技术租户记录 Owner，SaaS 只保存其不可变引用。
- 开户输入中的一次性密码禁止写入 SaaS 数据库、请求日志、CI 和普通制品。
- 所有数据库变更只通过新的前向 Flyway 迁移发布。
