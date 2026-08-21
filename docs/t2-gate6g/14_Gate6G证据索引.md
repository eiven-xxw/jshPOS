# Gate 6G 证据索引

| 证据 | 生成者 | 保留期 | 用途 |
| --- | --- | ---: | --- |
| gate6g-governance.json、CORE/API/DAT/INT 审计 | governance | 30 天 | 串行状态、主权和零外部执行 |
| Surefire、可执行 JAR、CycloneDX SBOM | server | 30 天 | Owner 正式执行与装配 |
| ReleaseMigrationMySqlIT | mysql | 30 天 | MySQL 8.4 空环境迁移 |
| Flutter machine、覆盖率、APK、供应链 | pos-linux | 30 天 | Linux/Android/SQLite/POS 正式代码 |
| Flutter machine | pos-windows | 30 天 | Windows 可重复性 |
| Vue JUnit、生产 index、许可证 | web | 30 天 | 后台正式构建与组件 |
| Server/Web/MySQL 同窗运行、Flutter 正式组合根与文件 SQLite | runtime-stack | 90 天 | 五组件同时存活及合成边界烟测 |
| 候选报告、seed、缺陷账、运行手册 | internal-v1-core | 90 天 | E2E-003 同 run 汇总 |
| Trivy、许可证策略 | security | 30 天 | Secret、漏洞和 IaC |
| 去重 SHA-256 索引 | evidence | 90 天 | 最终可追溯入口 |

CI run、Artifact ID 和最终 SHA-256 在流水线通过后回填周门禁报告与 RTM，不把本地 `artifacts/` 提交到仓库。
