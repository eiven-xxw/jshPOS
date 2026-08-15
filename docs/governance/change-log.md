# 已批准变更记录

| CR | 日期 | 状态 | 变更 | 影响需求/ADR | 批准人 |
|---|---|---|---|---|---|
| CR-T0-001 | 2026-08-15 | APPROVED | 建立 T0 治理、工程与 CI 基线；不引入正式业务逻辑 | T0-*、ADR-001—012 | 项目发起人指令 |
| CR-T0-002 | 2026-08-16 | APPROVED | 正式代码仓库与 T0 CI 从 Codeup/云效切换到 GitHub/GitHub Actions；Codeup 保留为可选镜像 | T0-REPO-001、T0-CI-001、T0-LIC-001、T0-BLD-001、ADR-013 | 项目发起人指令 |
| CR-T0-003 | 2026-08-16 | APPROVED | 修复 T0 SBOM 门禁发现的高危/严重依赖：采用 fastjson2 兼容包，升级 Netty、HttpCore、Bouncy Castle、PostgreSQL、Fory 并增加兼容性回归测试 | T0-SEC-001、T0-BLD-001、ADR-014 | 项目发起人“修复 CI 且不得降低安全阈值”指令 |

后续变更不得直接改表中历史记录；新增一行并在独立 CR 文档中保留分析与签字。
