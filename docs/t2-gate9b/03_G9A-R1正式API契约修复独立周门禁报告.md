# G9A-R1 正式 API 契约修复独立周门禁报告

- 状态：`VERIFIED_CANDIDATE_AWAITING_GITHUB_CI`
- 日期：2026-08-24
- 需求：`T2-API-001`（继续 `ACCEPTED`）
- 缺陷：`G9A-API-P1-001`
- 基线：`f708271e977f995e83a24fe398a1bd658726fd09`
- 证据上限：`STATIC_AND_SOFTWARE_EXECUTION`

## 结论

本地契约与完整 Server/Web 回归支持 `CONDITIONAL PASS` 候选：Controller/OpenAPI 从
`300/257、64/21` 收口至 `300/300、0/0`，没有修改 Controller、客户端业务代码或已发布迁移，
也没有新增 Requirement ID 或业务能力。最终结论仍须 GitHub Ubuntu/Windows、Flutter、Android、
MySQL/SQLite、安全和供应链完整 CI 独立复核。

## 已完成

- 项目发起人接受 `T2-CMP-001`，RTM 从 VERIFIED 更新为 ACCEPTED；
- ADR-069、CR-T2G9B-001、Gate 9B 准入与十 Owner 分类账建立；
- 七份当前 OpenAPI 对齐，Service/Subscription 历史文件名形成显式替代指针；
- 300/300 双向一致、operationId 全局唯一；
- 本批 80 项契约权限与 Controller 80/80 一致；
- 14 个客户端 API 根、契约测试和正式服务端全部可定位；
- `G9A-API-P1-001` 形成 `VERIFIED_CLOSURE_CANDIDATE`，原 Gate 9A 缺陷账保持不可变。

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
| Flutter/Android | 本机未安装固定 Flutter，必须由 GitHub 双平台执行，不能以本地缺失跳过最终门禁 |
| MySQL/SQLite | 本机无 Docker/Flutter，必须由 GitHub MySQL 8.4.11 与 SQLite Job 执行 |

## 状态边界

`T2-PAY-002/HWD-001/PRN-001/PAR-001` 保持 BLOCKED；`T2-UAT-001/REL-001` 保持
DRAFT；`T2-LIC-001/JSH-001` 保持 DEFERRED。Provider 网络、真实资金、设备/外设命令、
伙伴现场、完整 Alpha 和生产部署为 0。本报告不代表 FULL_ALPHA、PRODUCTION、COMMERCIAL
或商业 SLA。
