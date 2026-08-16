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
| CR-T1P-004 | 2026-08-16 | APPROVED | 支付候选池由不少于五家扩充为十家支付/聚合服务候选，并单列微信、支付宝、银联/云闪付三类首发支付能力；T1 从十家中评选五家做 Fake 契约，仍只准入一家真实沙箱，不扩大四周 PoC | T1-PAY-001、T1-PAY-002、ADR-017 | 项目发起人“支付候选多增加几个”指令 |
| CR-T1-001 | 2026-08-16 | APPROVED | 确认《T1 启动评审报告》并按 `CONDITIONAL GO` 启动四周 T1 风险 PoC；Week 1 只允许 READY 且通过准入的 STATIC/FAKE 契约、故障夹具与 CI，Blocked 实证、正式业务和 T2 继续禁止 | T1-GOV-001、T1-SCP-001、T1-HWD-001、T1-OFF-001、T1-SYN-001、T1-TEN-001、T1-PAY-001、T1-DPK-001、T1-UPG-001、T1-SEC-001、T1-CI-001 | 项目发起人 2026-08-16 明确确认 |
| CR-T1-002 | 2026-08-16 | IMPLEMENTED_AWAITING_CONFIRMATION | 将已通过准入的 9 个 Week 1 STATIC/FAKE 需求从 READY 转为 IN_PROGRESS，建立 8 份 PoC 契约、10 家支付候选/5 家 Fake、7 组故障夹具和 6-Job CI；仅回填 Week 1 分级证据，不解除任何实机、外设、支付沙箱或设计伙伴阻断 | T1-HWD-001、T1-OFF-001、T1-SYN-001、T1-TEN-001、T1-PAY-001、T1-DPK-001、T1-UPG-001、T1-SEC-001、T1-CI-001 | 待项目发起人确认 Week 1 周门禁报告 |
| CR-T1-003 | 2026-08-16 | APPROVED | 确认《T1 Week 1 周门禁报告》并按 `CONDITIONAL GO` 进入 Week 2；只允许 SQLite/Inbox、虚构租户攻击、10k/100k 合成数据包、虚构升级回退和五家支付 Fake 回归，所有外部实证、正式业务、Week 3 和 T2 继续禁止 | T1-OFF-001、T1-SYN-001、T1-TEN-001、T1-DPK-001、T1-UPG-001、T1-PAY-001、T1-SEC-001、T1-CI-001 | 项目发起人 2026-08-16 明确确认 |
| CR-T1-004 | 2026-08-16 | IMPLEMENTED_AWAITING_CONFIRMATION | 完成 Week 2 六类内部风险探针、2,879 条断言和 201,039 次核心迭代；GitHub Week 2 与 Week 1 回归全绿并封存 STATIC/FAKE 证据，未解除任何外部阻断 | T1-OFF-001、T1-SYN-001、T1-TEN-001、T1-DPK-001、T1-UPG-001、T1-PAY-001、T1-SEC-001、T1-CI-001 | 待项目发起人确认 Week 2 周门禁报告 |
| CR-T1-005 | 2026-08-16 | APPROVED | 确认《T1 Week 2 周门禁报告》并按 `CONDITIONAL GO` 进入 Week 3；只允许 Outbox/Inbox 交叉故障、支付 UNKNOWN/退款/对账 Fake、数据包恢复、升级兼容与前向修复、安全和失败 seed 回归，所有外部实证、正式业务、Week 4 和 T2 继续禁止 | T1-OFF-001、T1-SYN-001、T1-TEN-001、T1-DPK-001、T1-UPG-001、T1-PAY-001、T1-SEC-001、T1-CI-001 | 项目发起人 2026-08-16 明确确认 |
| CR-T1-006 | 2026-08-16 | IMPLEMENTED_AWAITING_CONFIRMATION | 完成 Week 3 内部交叉故障与恢复探针，1,425 条 FAKE 断言和 180,332 次迭代；GitHub Week 3 与 Week 1/2 回归全绿，证据包摘要独立复核一致，未解除任何外部阻断 | T1-OFF-001、T1-SYN-001、T1-TEN-001、T1-DPK-001、T1-UPG-001、T1-PAY-001、T1-SEC-001、T1-CI-001 | 待项目发起人确认 Week 3 周门禁报告 |
| CR-T1-007 | 2026-08-16 | APPROVED | 确认《T1 Week 3 周门禁报告》并按 `CONDITIONAL GO` 进入 Week 4；只允许 Ubuntu/Windows 重复验证、清理、安全与供应链摘要、证据总账和退出评审准备，所有外部实证、正式业务和 T2 继续禁止 | T1-*、ADR-018 | 项目发起人 2026-08-16 明确确认 |
| CR-T1-008 | 2026-08-16 | IMPLEMENTED_AWAITING_RETEST | Week 4 首轮 Ubuntu/Windows 的所有探针均通过，但比较门禁识别 Windows checkout 将 JSON 夹具转换为 CRLF，造成 fixture digest 不一致；增加 T1 PoC/契约 LF 属性并要求完整双平台重跑，不忽略摘要、不减少 seed 或断言 | T1-CI-001、T1-SEC-001、ADR-018 | Week 4 授权范围内的可重复性缺陷修复 |
| CR-T1-009 | 2026-08-16 | IMPLEMENTED_AWAITING_CONFIRMATION | Week 4 修复后完整重跑：Ubuntu/Windows 76 文件输入树、4,387 条断言、381,371 次迭代、失败 seed 总账和归一化证据摘要一致；六个 Week 4 Job 与 Week 1—3 回归全绿，证据包独立复核通过，外部阻断未解除 | T1-*、ADR-018 | 待项目发起人确认 Week 4 周门禁暨退出评审准备报告 |
| CR-T2P-001 | 2026-08-16 | APPROVED | 接受 T1 内部 STATIC/FAKE 风险基线 CONDITIONAL PASS，并仅按 CONDITIONAL GO 启动 T2-Prep；正式业务、tag 创建和商用声明继续禁止，外部阻断与许可证延期保持原状态 | T1-*、T2P-*、ADR-018—019 | 项目发起人 2026-08-16 明确确认 |
| CR-T2P-002 | 2026-08-16 | IMPLEMENTED_AWAITING_CI | 从 Week 4 最终封存提交建立 T2-Prep 候选分支，编制商业V1冻结清单、106项总RTM、六道模块门、详细设计31—40复核、14周计划、迁移测试CI、灰度回退和外部解阻材料；未修改正式业务目录且未创建tag | T2P-*、T2-*、ADR-019 | 等待T2-Prep GitHub门禁和启动评审 |
| CR-T2P-003 | 2026-08-16 | IMPLEMENTED_AWAITING_CONFIRMATION | T2-Prep Ubuntu治理、Windows边界、安全和证据四个Job全部通过；退出包摘要与GitHub一致且包内索引复核无差异，正式业务目录依赖和tag变化均为0 | T2P-*、T2-* | 等待项目发起人确认启动评审和候选tag |
| CR-T2P-004 | 2026-08-16 | APPROVED | 接受《T2正式开发启动评审报告》并授权创建 annotated tag `t2-prep-baseline-2026-08-16` 指向 `557ba270479935d6b44968cf70b47033f7d3d656`；仍禁止Gate 0/1和其他正式业务编码 | T2P-*、ADR-019 | 项目发起人2026-08-16明确确认 |
| CR-T2P-005 | 2026-08-16 | SEALED | annotated tag已创建推送；本地远端peeled commit一致且GitHub API可见tagger与完整message；T1状态及T2业务DRAFT/BLOCKED/DEFERRED保持不变 | T2P-* | Tag封存证据待最终治理CI复核 |
| CR-T2G0-001 | 2026-08-16 | APPROVED | 以封存 tag 为唯一不可变技术基线、以 `7fe4391` 为分支起点，按 CONDITIONAL GO 启动 Gate 0 / Sprint S0 八项平台基座需求；Gate 1 仅准入契约和测试准备，其他正式业务与外部实证继续禁止 | T2-IAM-001、T2-ORG-001、T2-RBAC-001、T2-CFG-001、T2-AUD-001、T2-SEC-001、T2-OBS-001、T2-MIG-001、ADR-019 | 项目发起人 2026-08-16 明确确认 |

后续变更不得直接改表中历史记录；新增一行并在独立 CR 文档中保留分析与签字。
