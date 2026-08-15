# 已批准变更记录

| CR | 日期 | 状态 | 变更 | 影响需求/ADR | 批准人 |
|---|---|---|---|---|---|
| CR-T0-001 | 2026-08-15 | APPROVED | 建立 T0 治理、工程与 CI 基线；不引入正式业务逻辑 | T0-*、ADR-001—012 | 项目发起人指令 |
| CR-T0-002 | 2026-08-16 | APPROVED | 正式代码仓库与 T0 CI 从 Codeup/云效切换到 GitHub/GitHub Actions；Codeup 保留为可选镜像 | T0-REPO-001、T0-CI-001、T0-LIC-001、T0-BLD-001、ADR-013 | 项目发起人指令 |
| CR-T0-003 | 2026-08-16 | APPROVED | 修复 T0 SBOM 门禁发现的高危/严重依赖：采用 fastjson2 兼容包，升级 Netty、HttpCore、Bouncy Castle、PostgreSQL、Fory 并增加兼容性回归测试 | T0-SEC-001、T0-BLD-001、ADR-014 | 项目发起人“修复 CI 且不得降低安全阈值”指令 |
| CR-T0-004 | 2026-08-16 | APPROVED | 按 Trivy 0.72.0 支持矩阵修正 SBOM 许可证命令：保留标准包许可证 HIGH/CRITICAL 阻断，移除 SBOM 目标不支持的全文扫描参数 | T0-CI-001、T0-LIC-001 | 项目发起人“修复 CI 且不得降低安全阈值”指令 |
| CR-T0-005 | 2026-08-16 | APPROVED | 建立受限许可证精确组件准入：移除未使用 MariaDB 数据源、升级 Aviator 安全补丁、增加机器可读清单与双层许可证门禁 | T0-LIC-001、T0-CI-001、ADR-015 | 项目发起人“核对许可证且不得降低门禁”指令 |
| CR-T0-006 | 2026-08-16 | APPROVED | 消除供应链扫描对 Maven 依赖的重复远程解析：Java 依赖以已构建聚合 SBOM 扫描，其他依赖单独扫描，密钥与 IaC 仍覆盖全仓库 | T0-LIC-001、T0-CI-001、T0-BLD-001 | 项目发起人“修复 CI 且不得降低安全阈值”指令 |
| CR-T0-007 | 2026-08-16 | APPROVED | 修复 Trivy DS-0002：三个 Java 运行镜像改用固定、不可登录的非 root 用户，并显式管理应用目录与 JAR 所有权 | T0-INF-001、T0-CI-001、T0-BLD-001、ADR-014 | 项目发起人“修复 CI 且不得降低安全阈值”指令 |
| CR-T0-008 | 2026-08-16 | APPROVED | GitHub Actions #34 六个 T0 Job 全绿并完成 APK/SBOM/许可证制品复核；回填最终证据、验收报告与 RTM `ACCEPTED` 状态，tag 继续等待项目发起人确认 | T0-* | 项目发起人“全部门禁后更新 ACCEPTED，创建 tag 前提交报告”指令 |
| CR-T0-009 | 2026-08-16 | APPROVED | GitHub 私有仓库未授权 Advanced Security，原生 Dependency Review 不可用；建立仓库自有 PR 依赖差异、SBOM、audit 与许可证门禁，不降低 HIGH/CRITICAL 阈值 | T0-CI-001、T0-LIC-001、T0-BLD-001、ADR-016 | 项目发起人“确保 dependency-review 通过且不得降低门禁”指令 |
| CR-T1P-001 | 2026-08-16 | APPROVED | 以 `t0-baseline-2026-08-16` 为唯一基线启动 T1-Prep；便利店为首发主样板，零食折扣店和社区超市为对照样板；T1 限定为四周风险 PoC，未经启动评审确认不得编码 | T1-*、ADR-017 | 项目发起人 2026-08-16 指令 |
| CR-T1P-002 | 2026-08-16 | APPROVED | 设备采用厂商无关适配层并预留主流品牌扩展；支付建立不少于五家聚合支付适配档案和 Fake 契约验证，但 T1 实付链路仅准入一个已授权真实沙箱；鲸熵汇资料延后 | T1-HWD-*、T1-PAY-*、T1-JSH-001、ADR-017 | 项目发起人 2026-08-16 指令 |
| CR-T1P-003 | 2026-08-16 | APPROVED | 不升级 GitHub 套餐并沿用补偿控制；受限许可证事项不阻断 T1-Prep/PoC，但保留为商业发布前阻断项 | T1-CI-001、T1-LIC-001、ADR-015—017 | 项目发起人 2026-08-16 指令 |

后续变更不得直接改表中历史记录；新增一行并在独立 CR 文档中保留分析与签字。
