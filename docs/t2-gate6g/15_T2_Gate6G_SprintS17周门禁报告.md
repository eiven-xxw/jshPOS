# T2 Gate 6G / Sprint S17 周门禁报告

## 1. 当前建议

Gate 6G 候选代码、治理、契约、正式运行栈和独立证据已收口。最终提交 `ac04afbc2236038fb73f99ba3a3ecd418ac7f5c5` 的 GitHub Actions run [`32456191093`](https://github.com/eiven-xxw/jshPOS/actions/runs/32456191093) 十类 Job 全绿，建议 `CONDITIONAL PASS`。五项需求保持 `VERIFIED`，等待项目发起人决定是否更新为 `ACCEPTED`。

证据上限严格为 `INTERNAL_V1_CORE_CANDIDATE`。它不更新 `T2-UAT-001/T2-REL-001`，也不代表支付沙箱、真实硬件、真实打印、设计伙伴、完整 Alpha、试点、生产或商用。

## 2. 串行结果

| Requirement | 状态 | 主要结果 |
| --- | --- | --- |
| T2-CORE-001 | VERIFIED | 55项已接受需求、15 Owner 覆盖审计；正式组合根和失败关闭 |
| T2-API-001 | VERIFIED | 167 Controller/167 OpenAPI；793错误码；租户覆写0 |
| T2-DAT-001 | VERIFIED | MySQL V1—V51、159表；SQLite 前向迁移和恢复 |
| T2-INT-001 | VERIFIED | 16模块可执行装配、20项协作、12 seed |
| T2-E2E-003 | VERIFIED 候选 | 六销售/六退货、正式 POS HTTP+签名包+文件SQLite链、12 seed、P0/P1=0 |

## 3. 本 Sprint 关键修复

1. 清除 Order/Promotion 跨 Owner 读取 Foundation 私表，改为正式只读端口。
2. 补齐历史表中文注释、索引/精度/租户元数据审计和空环境初始化治理。
3. 新建 `jshpos-integration` 组合根并校验每项能力唯一 Bean。
4. 补齐商品包、促销包、POS 本地销售、班次、退货预检和可信会话正式适配器。
5. POS 入口改用会话绑定组合根；安全凭据缺失、包校验失败或 SQLite 装配失败均不会解锁业务。
6. 建立同 run 内部候选、失败 seed、缺陷账、性能趋势与摘要证据链。
7. 由连续失败 run 暴露并关闭覆盖率、UTF-8 导入、RuoYi 菜单基线、Redis 认证、ULID Bean 注入及 SQLite 证据标题六项门禁缺陷；所有失败 run 均保留，没有只重跑失败 Job。

## 4. 外部与商业边界

- `T2-PAY-002/T2-HWD-001/T2-PAR-001/T2-PRN-001` 继续 `BLOCKED`。
- `T2-UAT-001/T2-REL-001` 与 V1 汇总项继续 `DRAFT`。
- `T2-LIC-001/T2-JSH-001` 继续 `DEFERRED`。
- Provider 网络、真实资金、真实终端命令、伙伴联系、现场试点、完整 Alpha 和生产部署均为 0。

## 5. CI 回填

| Job | 结果 | 时长 | 核心证据 |
| --- | --- | ---: | --- |
| governance | PASS | 8 秒 | RTM、契约、CORE/API/DAT/INT、零外部执行 |
| server | PASS | 455 秒 | 48 项 Reactor、Owner 回归、正式 JAR、CycloneDX SBOM |
| mysql | PASS | 109 秒 | MySQL 8.4、185 总表、52 条成功 Flyway 历史 |
| pos-linux | PASS | 299 秒 | 166 个成功测试、六档覆盖率、APK/Kotlin/供应链 |
| pos-windows | PASS | 172 秒 | 166 个成功测试、SQLite 跨平台复现 |
| admin-web | PASS | 69 秒 | 类型、ESLint、35 项组件测试、生产构建 |
| security | PASS | 19 秒 | Secret、漏洞、许可证、IaC |
| runtime-stack-smoke | PASS | 133 秒 | Server/Web/MySQL/Redis/Flutter 文件 SQLite 同窗运行 |
| internal-v1-core-candidate | PASS | 18 秒 | 六销售、六退货、12 seed、P0/P1=0、外部执行=0 |
| evidence | PASS | 28 秒 | 202 个文件去重摘要索引 |

关键制品：governance `9437320116`、server `9437487131`、mysql `9437358112`、pos-linux `9437428919`、pos-windows `9437380841`、web `9437342945`、runtime-stack `9437537658`、candidate `9437546416`、security `9437495773`、evidence-index `9437557706`。完整 ZIP SHA-256 见《Gate 6G 证据索引》。

候选报告自身 SHA-256 为 `9af2cd0ac164f9c677268b5c2ef077b5b5d5a7966e82eac3a1b4227a284f50fe`；执行模型为 `FORMAL_COMPONENT_EXECUTION_PLUS_CONTRACT_RECONCILIATION`。任一后续必需 Job 非绿色时，本报告结论自动降为 `NO-GO/IN_PROGRESS`。

## 6. 评审请求

请求项目发起人确认 Gate 6G `CONDITIONAL PASS`，并决定是否将五项需求从 `VERIFIED` 更新为 `ACCEPTED`。未经确认不得启动 Gate 6H、完整 Alpha、现场试点或生产发布。
