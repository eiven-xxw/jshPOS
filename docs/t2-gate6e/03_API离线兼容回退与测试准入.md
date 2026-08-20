# Gate 6E API、离线、兼容、回退与测试准入

## 1. API 边界

`T2-ADM-002` 只调用现有 `/api/v1/inventory`、`/api/inventory`、`/api/v1/procurement`、`/api/v1/inventory/transfers`、`/api/v1/promotions`、`/api/v1/members`、`/api/v1/reports`、`/api/v1/terminals` 和 `/api/v1/releases` 正式端点。缺少列表能力时使用显式 ID 查询，不用跨模块 SQL 或临时聚合端点绕过 Owner。

`T2-POS-009` 在后续准入时必须先冻结 Return/Refund 应用端口、SQLite 事务、错误码和状态映射；当前不得创建运行时。

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

## 4. 后续需求准入

`T2-POS-009` 与 `T2-E2E-002` 目前只保留本文范围和验收入口，继续 `DRAFT`。ADM-002 未形成独立 `VERIFIED` 证据前不得创建其正式页面、数据库、网络 Worker 或合成绿色结果。
