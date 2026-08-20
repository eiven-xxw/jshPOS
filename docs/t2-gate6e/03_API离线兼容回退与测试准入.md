# Gate 6E API、离线、兼容、回退与测试准入

## 1. API 边界

`T2-ADM-002` 只调用现有 `/api/v1/inventory`、`/api/inventory`、`/api/v1/procurement`、`/api/v1/inventory/transfers`、`/api/v1/promotions`、`/api/v1/members`、`/api/v1/reports`、`/api/v1/terminals` 和 `/api/v1/releases` 正式端点。缺少列表能力时使用显式 ID 查询，不用跨模块 SQL 或临时聚合端点绕过 Owner。

`T2-POS-009` 只允许通过 `PosReturnApplicationService` 使用 Return/Refund、Promotion、Payment、Inventory 与 Audit 的正式编排能力。页面和 Controller 不得访问 SQLite、HTTP、MethodChannel、Mapper，不得计算可退数量、恢复优惠或退款金额；应用服务返回的原单、可退上限、不可变促销摘要和 Saga 检查点是唯一展示依据。

## 2. 离线、兼容和回退

- 后台写操作断网时不进入离线队列，不猜测结果；UNKNOWN 使用同幂等键查询原结果。
- 只消费已发布 API/事件版本；未知字段忽略，未知状态失败关闭并提示升级。
- UI 发布通过路由/功能开关回退；不得修改已发布 Flyway/SQLite 迁移或用 UI 回退覆盖业务事实。

## 3. T2-ADM-002 测试准入

- API 契约：所有 Owner 路径、方法、权限和请求类型固定测试。
- 组件：加载、空态、权限拒绝、版本冲突、确认取消、单航班、错误恢复和关联标识。
- 攻击：跨租户/门店/仓库、请求注入、CSV 公式注入、缓存污染、对象存储越权、PII/Secret 泄漏。
- 边界：前端源码不得出现 Mapper、SQL、SQLite、MethodChannel、Provider URL/SDK 或领域金额/库存/成本/促销算法。
- 回归：TypeScript、ESLint、生产构建、Vitest、服务端、MySQL、Flutter 双平台、Android/Kotlin、安全、SBOM、许可证和 Gate 0—6D。

## 4. T2-POS-009 测试准入

- 原单：规范 ULID、本门店、本业务上下文、已完成状态、原促销快照身份和摘要。
- 上限：部分退、最终退、六位小数、累计数量/金额、最后一次合法退款吸收余数；页面只呈现 Owner 结果。
- 状态：`PENDING_APPROVAL/PROMOTION_PENDING/CASH_REFUND_PENDING/PAYMENT_PENDING/PAYMENT_UNKNOWN/INVENTORY_PENDING/COMPLETED/FAILED` 全量映射；未知状态失败关闭。
- 恢复：提交单航班；超时、ACK 丢失或进程重启后只查询原 `returnRef`，不得创建新命令；同键异内容由 Owner 拒绝。
- 安全：权限拒绝、跨租户/门店原单替换、摘要/金额篡改、凭据与错误脱敏、Provider 网络为零。

## 5. 后续需求准入

`T2-ADM-002` 已由 GitHub run `32376161860` 独立验证；`T2-POS-009` 已完成设计准入并进入 `IN_PROGRESS`。`T2-E2E-002` 继续 `DRAFT`，在 POS-009 独立 `VERIFIED` 前不得创建候选闭环运行时或绿色占位。
