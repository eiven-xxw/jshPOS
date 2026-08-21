# 正式 API 与运行时装配差距报告

## 1. 结论

T2-API-001 已完成独立内部验证，结论为 `PASS / VERIFIED`。正式运行目录共发现 167 项展开后的 Controller 路由，正式 OpenAPI 同样为 167 项，双向缺口均为 0；所有操作均有唯一 `operationId`。

本结论只证明内部 API 契约和软件执行边界，不代表 Provider 沙箱、真实硬件、完整 Alpha、试点或商业验收。

## 2. 已关闭差距

- 为 19 项已有运行时查询、治理和恢复端点补充 Gate 6G OpenAPI overlay，没有新增业务能力。
- 修正 Gate 2 POS/订单契约遗漏的 `/api/v1/pos` server 前缀；实际运行地址和客户端行为未改变。
- 为 Gate 4A 三项库存端点补齐唯一 `operationId` 和权限声明。
- 冻结统一可信租户、版本、幂等、关联标识、分页、排序、错误和事件兼容约定。
- 增加原单退货只读预检契约，冻结原订单、原促销快照、累计可退上限和金额守恒响应；补齐终端认证后的门店、时区、业务日、状态和凭据有效期。
- 扫描 793 个正式错误码，格式违规为 0；审计器要求错误文本使用 `CODE: message`，不会再把 `RULE:${id}` 等业务映射键误报为错误码；正式请求 DTO 中可作为租户覆写的字段为 0。
- 167 项端点均有权限或协议级认证说明；终端一次性激活和设备凭据认证在员工权限会话建立前完成，分别以一次性材料和登记凭据校验，均显式登记而非静默放行。

## 3. 保留差距与处置

| 等级 | 差距 | 当前处置 | 关闭点 |
| --- | --- | --- | --- |
| P2 | 成本 Owner 历史路径仍为 `/api/inventory`，未带版本号 | V1 内保留以避免破坏已接受客户端；禁止静默改址 | Gate 6H 只设计新增版本别名和弃用期 |
| 外部阻断 | 支付 Provider、真实回调、真实硬件接口没有执行证据 | 继续失败关闭，网络/命令计数为 0 | 各自独立执行准入后 |
| 内部候选 | 五组件分别执行并由同一 CI run 汇总；POS 正式组合根通过合成自有 HTTP 边界下载签名包并写文件 SQLite | 明确标记 `FORMAL_COMPONENT_EXECUTION_PLUS_CONTRACT_RECONCILIATION`，不冒充外部端到端 | 已由 run `32456191093` 形成 `INTERNAL_V1_CORE_CANDIDATE` |

当前不存在阻断 Gate 6G 内部候选的 API P0/P1 缺陷。正式可执行 JAR 已装配 15 个 Owner 与 `jshpos-integration` 组合根；Flutter 入口已装配正式会话、签名数据包、本地交易、同步和退货端口。外部支付、硬件、伙伴、打印和鲸熵汇边界仍以 `BLOCKED/DEFERRED + UNAVAILABLE` 失败关闭。

## 4. 证据

- `contracts/t2/gate6g/api-conventions-v1.json`
- `contracts/t2/gate6g/error-codes-v1.json`
- `contracts/t2/gate6g/event-conventions-v1.json`
- `contracts/t2/gate6g/openapi-internal-v1-overlay.yaml`
- `scripts/audit_t2_gate6g_api.py`
- 机器证据：`api-audit.json`，CI 生成并上传，不在仓库提交运行时产物。
- 最终独立证据：提交 `ac04afbc2236038fb73f99ba3a3ecd418ac7f5c5`，GitHub run [`32456191093`](https://github.com/eiven-xxw/jshPOS/actions/runs/32456191093)，十类 Job 全绿。
