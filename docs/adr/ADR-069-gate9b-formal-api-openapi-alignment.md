# ADR-069：Gate 9B 正式 API 与当前 OpenAPI 双向一致性

- 状态：Accepted
- 日期：2026-08-24
- 关联：T2-API-001、T2-CMP-001、G9A-API-P1-001
- 工作基线：`f708271e977f995e83a24fe398a1bd658726fd09`

## 背景

Gate 9A 在 300 项正式 Controller 操作中识别出 64 项缺少当前 OpenAPI，同时当前 257 项
OpenAPI 中有 21 项无法精确匹配 Controller。客户端 14 个 API 根均有正式服务端承载，因此
本批缺陷主要是契约文件发现规则、历史路径、参数名和通用 action 路径与当前运行时发生漂移，
不是批准新增业务能力的依据。

## 决策

1. 继续以已接受的 `T2-API-001` 作为需求权威，`G9A-API-P1-001` 只是缺陷修复标识，不新增
   Requirement ID、业务状态机、数据库表、迁移、权限或客户端旅程。
2. 以当前正式 Controller、已接受 Owner 行为和 Vue/Flutter 客户端调用的交集确定当前契约；
   每项差异必须归类为补齐当前契约、修正实现、历史草案/替代契约或经审批删除的孤立能力。
3. 本批优先修正 OpenAPI。只有契约回归先证明实现错误时才允许修改 Controller；禁止为了计数
   归零静默删除正式操作、放宽权限或改变资金、库存、租户、幂等和失败关闭语义。
4. 当前 OpenAPI 文件必须使用 `openapi-*.yaml` 命名并提供唯一 `operationId`；历史草案须显式
   标注 `x-contract-authority: HISTORICAL_DRAFT_NON_RUNTIME` 与有效 `x-superseded-by`。
5. 双向比较使用精确 HTTP method 与规范化路径，路径参数名属于兼容契约；通用 action 路径只有
   在 Controller 也以同一精确路径公开时才可作为当前契约，不能替代多个具名操作。
6. Gate 9A 原始审计、历史 Gate 6G 审计器和失败证据保持不可变；Gate 9B 用独立闭环证据记录
   85 项基线差异、分类、修复结果和 `0/0` 退出状态。

## 后果与验证

- 300 项 Controller 与当前 OpenAPI 必须双向一一对应，`operationId` 必须全局唯一；
- Vue/Flutter 页面调用必须可定位到正式 Controller 与当前 OpenAPI；
- 权限、可信 `tenant_id`、错误码、幂等键、版本与历史替代契约必须通过机器回归；
- 不得修改已发布 MySQL/SQLite 迁移，不得新增业务能力或外部网络/设备行为；
- 完成后只把 `G9A-API-P1-001` 记录为 `CLOSED_IN_GATE9B` 候选，提交独立周门禁报告等待确认。
