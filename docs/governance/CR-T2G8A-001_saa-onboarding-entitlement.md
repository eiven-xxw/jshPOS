# CR-T2G8A-001：T2-SAA-001 商户开户、套餐权益与租户生命周期

- 状态：PREPARED_CONDITIONAL_GO_AWAITING_SPONSOR
- 日期：2026-08-23
- Requirement：T2-SAA-001
- 基线：`b47533eba707d486abe44dbf70ec7b651081b3af`
- 本次证据：`STATIC_DESIGN_AND_CONTRACT_PREP`

## 1. 商业价值与三业态范围

便利店、零食折扣店和社区超市都需要一致的商户申请、审批、租户初始化、套餐权益、暂停与
恢复能力，否则现有内部业务模块只能由开发人员手工装配，无法形成可重复销售和实施的 SaaS
产品。能力三业态共用，行业差异只通过既有模板引用，不复制开户状态机。

## 2. 推荐边界

新建独立 `jshpos-saas` Owner。它独占商户申请、套餐/权益版本、开户计划、生命周期编排、
幂等结果、审计和 Outbox；Foundation 继续独占技术租户记录、可信租户上下文、组织门店、
IAM/RBAC 与平台审计适配。SaaS 只能调用 `TenantProvisioningPort`，禁止直接写 `sys_tenant`、
RuoYi 系统表或业务 Owner 私有表。

tenant_id 由服务端 Foundation 分配并回传，申请 DTO、Header、缓存、任务、导出或对象路径中
的 tenant_id 均不得成为授权依据。套餐权益在认证后、业务应用服务执行前由服务端判定；
前端菜单隐藏只能改善体验，不能代替授权。

## 3. 状态与关键不变量

商户申请按 `DRAFT → PREFLIGHTING → READY → APPROVED → PROVISIONING → INITIALIZING → ACTIVE`
推进，失败进入显式 `PREFLIGHT_FAILED/FAILED/COMPENSATION_REQUIRED`。ACTIVE 后暂停、停用、
恢复和注销只能生成只追加生命周期事实；已产生业务事实的租户禁止物理删除或复用 tenant_id。

套餐与权益版本必须预检、独立审批、发布、未来生效和不可变；同范围同时间只能有一个有效
版本。配额覆盖门店、终端、员工、存储、导出和 API，但不得改变金额、库存、支付等领域算法。
暂停/到期仍必须保留退款、对账、备份恢复、法定导出和数据迁移所需的受控能力。

## 4. 影响、风险与非目标

影响 Foundation、IAM、ORG、RBAC、Terminal、Reporting、Backup、Migration、Vue 和 Flutter
提示契约；不影响现有业务表主权。最大风险是租户误停、越权开通、配额竞态和商业状态侵入
RuoYi。通过职责分离、稳定幂等键、乐观锁、只追加历史、可信端口和失败关闭控制。

不包含真实订阅收费、自动扣款、发票、应付、总账、税务、储值、生产租户开户、连接器或
商业 SLA。

## 5. Go/No-Go

建议 `CONDITIONAL GO`，但本 CR 只完成准备。项目发起人确认前 Requirement 保持 `DRAFT`，
不得创建模块、迁移、Controller、页面或任务。确认后只准入 T2-SAA-001，SUB/SVC 继续 DRAFT。
