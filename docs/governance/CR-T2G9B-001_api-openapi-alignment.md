# CR-T2G9B-001：G9A-R1 正式 API 与 OpenAPI 一致性修复

- 状态：APPROVED_RUNTIME_REPAIR
- 日期：2026-08-24
- 发起人：项目发起人
- 基线：`f708271e977f995e83a24fe398a1bd658726fd09`
- 分支：`t2/gate9b-sprint27a-api-openapi-alignment`
- 复用需求：`T2-API-001`
- 缺陷：`G9A-API-P1-001`

## 已批准范围

1. 冻结 300 项 Controller、257 项当前 OpenAPI、14 个客户端 API 根以及 64/21 双向差异；
2. 按 Owner 串行分类并修复当前契约入口、精确路径、参数名、具名 action 与 operationId；
3. 先增加契约回归，再在确有实现错误时修正 Controller；
4. 增加权限、可信租户、错误码、幂等、版本兼容与客户端正式服务端覆盖门禁；
5. 保留 Gate 9A 原始缺陷账和历史 Gate 6G 审计器，使用 Gate 9B 独立关闭证据。

## 明确非目标

- 不新增 Requirement ID、业务能力、Controller、页面旅程、领域状态机、数据库表或迁移；
- 不改变资金、库存、租户、权限、幂等、错误和失败关闭语义；
- 不处理 G9A-R2 至 R4；
- 不执行 Provider 网络、真实资金、设备/外设、伙伴现场、完整 Alpha 或生产发布。

## 验收

- Controller/OpenAPI 双向差异为 `0/0`，300 项操作均有唯一 operationId；
- 14 个客户端 API 根及其页面调用均有正式 Controller 和当前 OpenAPI；
- 历史草案替代关系有效，权限、租户、错误码、幂等和兼容回归全绿；
- 治理、Server、Web、Flutter 双平台、Android/Kotlin、MySQL、SQLite、安全、依赖、SBOM、
  许可证、覆盖率和既有 Gate 回归全绿；
- 只形成 `G9A-API-P1-001 CLOSED_IN_GATE9B` 候选并等待项目发起人确认。
