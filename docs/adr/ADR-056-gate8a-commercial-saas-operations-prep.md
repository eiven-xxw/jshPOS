# ADR-056：Gate 8A 商业 SaaS 运营 Owner 与串行准入边界

- 状态：Accepted
- 日期：2026-08-23
- 范围：T2-SAA-001、T2-SUB-001、T2-SVC-001 准备阶段
- 基线：`b47533eba707d486abe44dbf70ec7b651081b3af`

## 背景

Gate 7E 已完成内部商业 V1 业务候选，但缺少将商户从申请、开户、套餐权益、订阅生命周期到
实施服务可重复运营的产品能力。三项在 RTM 中已有唯一 ID，但均要求独立 CR，且存在明确的
SAA → SUB → SVC 依赖。

## 决策

1. 采用三个模块化单体 Owner：`jshpos-saas`、`jshpos-subscription`、`jshpos-service`；每个
   Owner 独占自己的事实、Repository、Mapper、表、审计和 Outbox，不跨 Owner Mapper。
2. Foundation/RuoYi 继续拥有认证会话、技术租户记录、可信上下文、组织门店和 RBAC 适配；
   SaaS 通过 `TenantProvisioningPort` 编排，禁止把商业规则写入 `ruoyi-system`。
3. 套餐权益是版本化商业政策，不是认证身份或前端菜单。服务端在可信租户上下文建立后通过
   `EntitlementDecisionPort` 判定；核心金额、库存、支付状态机不按套餐分叉。
4. Subscription 只管理期限和状态，不处理资金。它通过 SaaS 端口请求权益切换/降级，不能
   直接更新租户或套餐事实。
5. Service 只管理实施与工单，不拥有租户、订阅、设备、伙伴授权或生产变更事实；附件使用
   租户命名空间对象引用和独立授权。
6. 暂停、到期和终止均不得物理删除业务历史，并必须保留退款、查询、对账、审计、备份、
   导出、迁移和清除所需的最小受控能力。
7. Gate 8A-Prep 只生成 DRAFT 非执行契约、迁移设计和测试向量；运行时、迁移、页面和任务
   变更数固定为 0。三项状态保持 DRAFT，项目发起人逐项确认后才能串行编码。

## 后果

- 增加三个独立模块会提高边界清晰度和测试成本，但避免 Foundation/RuoYi 成为商业逻辑杂物箱。
- `T2-PAY-002/HWD-001/PRN-001/PAR-001` 继续 BLOCKED；UAT/REL DRAFT；LIC/JSH DEFERRED。
- 真实收费、外部执行、完整 Alpha 和生产发布均不在本 ADR 授权范围内。
