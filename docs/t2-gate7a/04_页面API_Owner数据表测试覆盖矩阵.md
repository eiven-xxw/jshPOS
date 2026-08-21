# 页面 / API / Owner / 数据表 / 测试覆盖矩阵

## 1. 已接受能力的逐项来源

`scripts/audit_t2_gate6g_core.py` 对所有 64 项 `ACCEPTED` 需求逐项生成以下字段：`page`、`api`、`application`、`domain`、`repositoryOrMapper`、`migration`、`test`。本次复跑结果为：15 个 Owner、0 条硬失败。纯后端能力的页面字段使用 `N/A_BY_REQUIREMENT_SCOPE`，不得用空值伪装页面覆盖。

为了避免把 64 行长路径复制成易漂移文档，本文件按能力族汇总；机器源仍由上述脚本在 CI 现场从仓库生成。能力族静态索引见 `contracts/t2/gate7a/v1-capability-family-matrix.csv`。

## 2. 新差距目标矩阵

下表只冻结目标边界，不表示设计已准入。具体名称在 READY 前必须进入版本化 OpenAPI/事件和持久化登记。

| Requirement | 页面/旅程目标 | API/事件目标 | Owner 与数据主权 | Schema/迁移目标 | 必测故障与攻击 |
|---|---|---|---|---|---|
| `T2-POS-010` | 班次现金管理、二次确认、审批 | 现金移动命令/事件 | Order/POS 只写班次现金事实 | SQLite+MySQL 只追加流水 | 重复、同键异内容、越权、关班并发、磁盘失败 |
| `T2-POS-011` | 小票预览、失败重试、补打 | 打印任务/补打事件 | POS 写任务；Device 只执行 | SQLite 打印任务历史 | ACK 未知、重复点击、模板变更、跨租户、假成功拒绝 |
| `T2-ORD-004` | 取消确认、完成订单只显示退货入口 | 取消命令/状态事件 | Order 写状态；Return 写反向事实 | 状态历史与幂等结果 | 资金成功竞态、库存已出、重复取消、崩溃恢复 |
| `T2-EXG-001` | 原单退货和新售单关联 | 换货编排事件 | Return/Order 各写自己的事实 | 只追加关系与 Saga 检查点 | 部分失败、价格变化、退款 UNKNOWN、重放 |
| `T2-PAY-004` | 多方式份额、未付余额、恢复 | tender plan/attempt 事件 | Payment 写份额与尝试；Order 只读结果 | 支付份额只追加事实 | UNKNOWN、部分成功、超额、舍入、重启、跨租户 |
| `T2-PRD-005` | 秤码扫描与人工复核 | 编码模板/解析结果契约 | Catalog 写模板；Order 冻结结果 | 模板版本与成交快照字段 | 前导零、校验位、精度、金额/重量篡改、过期模板 |
| `T2-LBL-001` | 价签任务、预览、未换签列表 | label task/price changed | Catalog/Pricing 写任务来源；Device 只执行 | 任务与执行历史 | 旧价重放、价格版本漂移、重复打印、跨店越权 |
| `T2-RPL-001` | 库存预警、建议审批、转采购草稿 | suggestion/approved/drafted | Inventory 提供只读量；Procurement 写草稿 | 建议版本和依据快照 | 晚到销售、并发审批、负数、重复转单、跨仓污染 |
| `T2-DMT-001` | 字段映射、预检、差异签署、切换 | migration batch 契约 | Migration 编排；各 Owner 验证并写自己的事实 | staging/批次/错误/检查点 | 重复文件、半批次、摘要错、断点、回退、租户替换 |
| `T2-ONB-001` | 门店复制向导与开店检查 | clone plan/milestone | Foundation 写模板引用和门店里程碑 | 计划、步骤、结果和审计 | 源店变化、重复执行、部分失败、跨租户模板、前向修复 |
| `T2-LOT-001` | 批次收货、效期查询、临期任务 | lot/expiry 事件 | Inventory/Catalog；成本仍沿用既有 Owner | 条件维度与只追加流水 | FEFO、过期禁售、退货归批、离线旧包、跨批次污染 |
| `T2-CLS-001` | 日结预检、差异、审批和签署 | business-day close 事件 | Operations 写日结；只读 Owner 汇总 | 关账头、差异、签署、Outbox | 晚到交易、未关班、重复关账、跨夜、投影缺口 |
| `T2-EXC-001` | 异常队列、认领、处置、关闭 | exception task 事件 | Operations 写任务；不得写其他 Owner | 任务、状态历史、审计、引用 | 重复异常、权限、超时、并发认领、修复失败、敏感泄露 |
| `T2-MEM-003` | 权益展示与成交权益摘要 | benefit/price quote 事件 | Member 写权益；Pricing/Promotion 计算；Order 冻结 | 权益版本和订单快照引用 | 等级晚到、撤回、离线过期、退款、跨会员替换 |
| `T2-SAA-001` | 商户开户、套餐授权和停用 | merchant/entitlement 事件 | 新 SaaS Owner；RuoYi 仅平台适配 | 商业账户、权益版本、历史 | 越权开户、到期竞态、重复授权、租户逃逸 |
| `T2-SUB-001` | 订阅续期、宽限和降级 | subscription lifecycle | SaaS/Commercial Owner | 合同权益只追加历史 | 时区边界、重复续期、降级中断、恢复、审计 |
| `T2-SVC-001` | 实施清单和服务工单 | project/ticket 事件 | Service Owner | 工单、步骤、状态历史 | 越权、附件泄漏、重复关闭、SLA 假声明拒绝 |
| `T2-E2E-004` | 三业态内部完整旅程 | 仅调用正式 API/事件 | 不新增事实 Owner | 不得用测试后门建表 | 全链故障 seed、守恒、P0/P1、重建、前向修复 |

## 3. 共通权限和审计边界

- `tenant_id` 始终来自可信上下文；请求体、Header、缓存键、任务、导出和对象路径中的租户值不能作为授权依据。
- 高风险动作至少覆盖现金支出/存现、补打、取消/反向处置、换货、组合支付、批次禁售绕过、日结、异常关闭、套餐降级。
- 审计必须记录关联标识、对象版本、幂等键摘要、原因、操作者、审批人、业务日和结果；不得记录 Secret、支付敏感数据或不必要 PII。
- 新表在编码前必须登记访问策略和 SQL 模式；简单 CRUD 用 MyBatis-Plus，复杂聚合/锁/重建用 XML，均不得跨 Owner。

## 4. 容量与兼容基线

- 金额继续使用最小货币单位整数；单价/成本/数量/换算使用 `DECIMAL/BigDecimal`，禁止浮点数。
- MySQL/SQLite 只允许前向迁移；已发布迁移摘要必须保持不变。
- 秤码、价签、批次、权益、日结和订阅契约都必须带版本/摘要；旧客户端不理解新能力时失败关闭或保持旧兼容窗口，禁止猜测默认成功。
- Gate 7A 没有创建表、端点或事件，所列名称均须在对应 Gate READY 评审时最终冻结。
