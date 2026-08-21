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

最终候选提交：`ac04afbc2236038fb73f99ba3a3ecd418ac7f5c5`

GitHub Actions：[`32456191093`](https://github.com/eiven-xxw/jshPOS/actions/runs/32456191093)，十类 Job 全绿。

| Artifact | ID | ZIP SHA-256 | 大小（字节） |
| --- | ---: | --- | ---: |
| t2-gate6g-governance | 9437320116 | `69d8fafca7f1191ec233706ca06b710b7a0469e89785dc7bd063bfb522c89f34` | 59,376 |
| t2-gate6g-server | 9437487131 | `ac0ccb149e11ed73ae8ecdf65896af29174e31f1adebdbd0da7b84c49bf3186a` | 155,255,108 |
| t2-gate6g-mysql | 9437358112 | `d218fbd8dfc75cb865d608b1ec1379a4845c95e5aae3f4458da484cb878aa7ea` | 6,704 |
| t2-gate6g-pos-linux | 9437428919 | `2ede65ecec80627e547e20024cf855952c5cce37e8d3a72cea06cc485b499911` | 76,924,577 |
| t2-gate6g-pos-windows | 9437380841 | `0b899258e5e3b7075c11a640564de0f14266e1b6cc17cfbeec3b3ed32c4f8fce` | 10,961 |
| t2-gate6g-web | 9437342945 | `bbf43c3113cc000b3aca97560f9b5c7a878545c7a74c33cfe11e70d80f3bc959` | 81,090 |
| t2-gate6g-runtime-stack | 9437537658 | `68ceb2a0f8d23467ffd7ede0dbdeccb1067f542dfff23a25d79d80e1f404c9a2` | 56,367 |
| t2-gate6g-internal-v1-core-candidate | 9437546416 | `1ad123046cf1edfbfaf49c5e982e70af7d442d09d50e17babee7d827b5dd137e` | 4,443 |
| t2-gate6g-security | 9437495773 | `85bad29914b3d417a558b346a8a752924923ac5c97d3c44b52b8adbb8a22e2cb` | 92,278 |
| t2-gate6g-evidence-index | 9437557706 | `e3ec7f5a65ed5e71d45085fb0f23736e721ee2402f5ec27eeea62ebe3c4b2952` | 11,951 |

候选报告自身摘要为 `9af2cd0ac164f9c677268b5c2ef077b5b5d5a7966e82eac3a1b4227a284f50fe`；最终去重索引覆盖 202 个文件。运行栈、候选和最终索引保留至 2026-11-19，其余制品保留约 30 天。本地 `artifacts/` 与临时下载包不提交仓库。
