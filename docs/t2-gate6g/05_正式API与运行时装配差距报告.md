# 正式 API 与运行时装配差距报告

## 1. 结论

T2-API-001 已完成独立内部验证，结论为 `PASS / VERIFIED`。正式运行目录共发现 164 项展开后的 Controller 路由，正式 OpenAPI 同样为 164 项，双向缺口均为 0；所有操作均有唯一 `operationId`。

本结论只证明内部 API 契约和软件执行边界，不代表 Provider 沙箱、真实硬件、完整 Alpha、试点或商业验收。

## 2. 已关闭差距

- 为 19 项已有运行时查询、治理和恢复端点补充 Gate 6G OpenAPI overlay，没有新增业务能力。
- 修正 Gate 2 POS/订单契约遗漏的 `/api/v1/pos` server 前缀；实际运行地址和客户端行为未改变。
- 为 Gate 4A 三项库存端点补齐唯一 `operationId` 和权限声明。
- 冻结统一可信租户、版本、幂等、关联标识、分页、排序、错误和事件兼容约定。
- 扫描 737 个正式错误码，格式违规为 0；正式请求 DTO 中可作为租户覆写的字段为 0。
- 164 项端点均有权限或协议级认证说明；唯一例外是终端一次性激活端点，它在员工权限建立前以一次性激活凭据完成认证，已显式登记而非静默放行。

## 3. 保留差距与处置

| 等级 | 差距 | 当前处置 | 关闭点 |
| --- | --- | --- | --- |
| P2 | 成本 Owner 历史路径仍为 `/api/inventory`，未带版本号 | V1 内保留以避免破坏已接受客户端；禁止静默改址 | Gate 6H 只设计新增版本别名和弃用期 |
| 外部阻断 | 支付 Provider、真实回调、真实硬件接口没有执行证据 | 继续失败关闭，网络/命令计数为 0 | 各自独立执行准入后 |
| 串行待办 | 数据迁移、Owner 装配和真实五组件内部候选尚未在 API 阶段宣称通过 | T2-DAT-001 现为唯一 IN_PROGRESS | DAT→INT→E2E 逐项验证 |

当前不存在阻断 T2-DAT-001 的 API P0/P1 缺陷。

## 4. 证据

- `contracts/t2/gate6g/api-conventions-v1.json`
- `contracts/t2/gate6g/error-codes-v1.json`
- `contracts/t2/gate6g/event-conventions-v1.json`
- `contracts/t2/gate6g/openapi-internal-v1-overlay.yaml`
- `scripts/audit_t2_gate6g_api.py`
- 机器证据：`api-audit.json`，CI 生成并上传，不在仓库提交运行时产物。
