# 已批准变更记录

| CR | 日期 | 状态 | 变更 | 影响需求/ADR | 批准人 |
|---|---|---|---|---|---|
| CR-T0-001 | 2026-08-15 | APPROVED | 建立 T0 治理、工程与 CI 基线；不引入正式业务逻辑 | T0-*、ADR-001—012 | 项目发起人指令 |
| CR-T0-002 | 2026-08-16 | APPROVED | 正式代码仓库与 T0 CI 从 Codeup/云效切换到 GitHub/GitHub Actions；Codeup 保留为可选镜像 | T0-REPO-001、T0-CI-001、T0-LIC-001、T0-BLD-001、ADR-013 | 项目发起人指令 |
| CR-T0-003 | 2026-08-16 | APPROVED | 修复 T0 SBOM 门禁发现的高危/严重依赖：采用 fastjson2 兼容包，升级 Netty、HttpCore、Bouncy Castle、PostgreSQL、Fory 并增加兼容性回归测试 | T0-SEC-001、T0-BLD-001、ADR-014 | 项目发起人“修复 CI 且不得降低安全阈值”指令 |
| CR-T0-004 | 2026-08-16 | APPROVED | 按 Trivy 0.72.0 支持矩阵修正 SBOM 许可证命令：保留标准包许可证 HIGH/CRITICAL 阻断，移除 SBOM 目标不支持的全文扫描参数 | T0-CI-001、T0-LIC-001 | 项目发起人“修复 CI 且不得降低安全阈值”指令 |
| CR-T0-005 | 2026-08-16 | APPROVED | 建立受限许可证精确组件准入：移除未使用 MariaDB 数据源、升级 Aviator 安全补丁、增加机器可读清单与双层许可证门禁 | T0-LIC-001、T0-CI-001、ADR-015 | 项目发起人“核对许可证且不得降低门禁”指令 |
| CR-T0-006 | 2026-08-16 | APPROVED | 消除供应链扫描对 Maven 依赖的重复远程解析：Java 依赖以已构建聚合 SBOM 扫描，其他依赖单独扫描，密钥与 IaC 仍覆盖全仓库 | T0-LIC-001、T0-CI-001、T0-BLD-001 | 项目发起人“修复 CI 且不得降低安全阈值”指令 |

后续变更不得直接改表中历史记录；新增一行并在独立 CR 文档中保留分析与签字。
