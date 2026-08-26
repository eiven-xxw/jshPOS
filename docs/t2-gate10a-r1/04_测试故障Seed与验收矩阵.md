# 测试、故障 Seed 与验收矩阵

## 冻结失败 seed

| seed | 初始观察 | 修复后的预期 |
|---|---|---|
| G10A-R1-ACT-001 | 1,892 条旧版或账外 Action 引用违规 | 全部远程引用与单一账本一致 |
| G10A-R1-ACT-002 | 172 条 `setup-java v4` 引用 | `setup-java v6.0.0` 固定 SHA，旧引用为 0 |
| G10A-R1-DEP-001 | 4 个技术栈尚未 clean-run 复验 | 四栈按顺序完成且输入摘要不漂移 |
| G10A-R1-CI-001 | 首轮 Run 32940429973 暴露 8 个 Windows/Ubuntu 换行摘要差异 | 改用 Git blob 规范字节，两平台摘要一致 |
| G10A-R1-KOT-001 | Run 32940754079 错误假定 example 的已忽略生成式 `gradlew` 已跟踪 | 使用正式 POS 已跟踪 wrapper 验证插件工程、Kotlin 单测与 APK |

原始 seed 保存在 `contracts/t2/gate10a-r1/failure-seeds-v1.json`，不得删除或改写为从未失败。

## 验收矩阵

| 阶段 | 必测内容 | 失败关闭条件 | 核心制品 |
|---|---|---|---|
| Governance | tag、ADR、范围、外部状态、Action 账、依赖摘要 | 任一漂移 | Ubuntu/Windows scope log |
| Maven | clean verify、依赖树、升级候选、聚合 SBOM | 测试/构建/摘要失败 | tree、updates、bom、snapshot |
| pnpm | frozen install、build、lint、typecheck、unit、audit、license | lock 漂移、高危漏洞或测试失败 | audit、license、JUnit、snapshot |
| Flutter Pub | 两包 locked get、format、analyze、test、供应链 | 两平台任一失败 | deps、outdated、tests、snapshot |
| Kotlin/Gradle | wrapper、依赖树、unit、adapter APK、POS APK | 测试或 APK 构建失败 | Gradle tree、APK、snapshot |
| Security | SBOM 漏洞/许可证、repo 漏洞、Secret、IaC | 原阈值任一失败 | Trivy 与许可证证据 |
| Gate 9C regression | 88 RTM、300 API、26 页面、22 Owner | 任一既有基线退化 | 机器审计输出 |
| Evidence | 九个生产者完整聚合 | 缺生产者、空制品或摘要失败 | evidence-index.json |

CI 采用严格依赖链，禁止自动重跑掩盖 Flaky。每次修复必须生成新提交并从头运行。
