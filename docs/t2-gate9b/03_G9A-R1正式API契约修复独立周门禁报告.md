# G9A-R1 正式 API 契约修复独立周门禁报告

- 状态：`VERIFIED_CONDITIONAL_PASS_AWAITING_CONFIRMATION`
- 日期：2026-08-24
- 需求：`T2-API-001`（继续 `ACCEPTED`）
- 缺陷：`G9A-API-P1-001`
- 基线：`f708271e977f995e83a24fe398a1bd658726fd09`
- 证据上限：`STATIC_AND_SOFTWARE_EXECUTION`

## 结论

Controller/OpenAPI 已从 `300/257、64/21` 收口至 `300/300、0/0`，没有修改 Controller、
客户端业务代码或已发布迁移，也没有新增 Requirement ID 或业务能力。候选提交
`5ebcde37f753193757f6cf9de0ae960808f406b7` 的 GitHub Run `32728598791` 已从头完成
Ubuntu/Windows、Server、Web、Flutter、Android/Kotlin、MySQL/SQLite、安全、SBOM、许可证与
证据聚合并全绿，支持 `G9A-API-P1-001 CONDITIONAL PASS`，等待项目发起人确认关闭。

## 已完成

- 项目发起人接受 `T2-CMP-001`，RTM 从 VERIFIED 更新为 ACCEPTED；
- ADR-069、CR-T2G9B-001、Gate 9B 准入与十 Owner 分类账建立；
- 七份当前 OpenAPI 对齐，Service/Subscription 历史文件名形成显式替代指针；
- 300/300 双向一致、operationId 全局唯一；
- 本批 80 项契约权限与 Controller 80/80 一致；
- 14 个客户端 API 根、契约测试和正式服务端全部可定位；
- `G9A-API-P1-001` 已达到 `VERIFIED`，原 Gate 9A 缺陷账保持不可变，正式关闭仍需项目发起人确认。

## 本地测试

| 门禁 | 结果 |
|---|---|
| 治理/RTM/OpenAPI | PASS |
| 历史 API 审计 | PASS：300/300，hardFailures=0 |
| Gate 9B 独立审计 | PASS：差异0/0、权限80、客户端根14、错误码1388 |
| Server 完整 Maven verify | PASS：260 份报告、854 tests、0 failure/error/skipped |
| Web typecheck | PASS |
| Web Vitest | PASS：25 files、71 tests |
| Web production build | PASS |
| Web ESLint | PASS |
| GitHub 治理 Ubuntu/Windows | PASS |
| GitHub Server | PASS：完整 Maven verify、覆盖率、聚合 SBOM 与许可证门禁 |
| GitHub Web | PASS：build/lint/typecheck/Vitest/许可证 |
| GitHub Flutter Ubuntu/Windows | PASS：format/analyze/test/覆盖率/供应链 |
| GitHub Android/Kotlin | PASS：Kotlin 编译、debug APK 构建及摘要 |
| GitHub MySQL/SQLite | PASS：MySQL 8.4.11 前向迁移与 SQLite 恢复契约 |
| GitHub Security | PASS：依赖漏洞、Secret、工作流、SBOM 与许可证 |
| GitHub Evidence | PASS：9 个 Artifact；Evidence Index `9520855018` |

## 封板证据

- GitHub Run：<https://github.com/eiven-xxw/jshPOS/actions/runs/32728598791>
- 候选提交：`5ebcde37f753193757f6cf9de0ae960808f406b7`
- Evidence Artifact：`9520855018`
- Evidence Artifact SHA-256：`5344766fb9d2bf07f01e61a51aa27424f18ecfb30305f72a02ae9d4725e603f8`
- Run 结论：`success`，9 个具名 Artifact，未使用失败 Job 重跑或自动重试。

## 状态边界

`T2-PAY-002/HWD-001/PRN-001/PAR-001` 保持 BLOCKED；`T2-UAT-001/REL-001` 保持
DRAFT；`T2-LIC-001/JSH-001` 保持 DEFERRED。Provider 网络、真实资金、设备/外设命令、
伙伴现场、完整 Alpha 和生产部署为 0。本报告不代表 FULL_ALPHA、PRODUCTION、COMMERCIAL
或商业 SLA。
