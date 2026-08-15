# ADR-016：私有仓库的 PR 依赖评审门禁

- 状态：Accepted
- 日期：2026-08-16

## 背景

GitHub 原生 `actions/dependency-review-action` 在 T0 证据 PR #16 上真实执行后返回：私有仓库必须同时启用 Dependency Graph 和 GitHub Advanced Security，否则不支持 Dependency Review。当前仓库套餐也不提供 Branch Protection。失败运行是 <https://github.com/eiven-xxw/jshPOS/actions/runs/31899680269>；这属于平台能力限制，不是“未发现风险”的成功结果。

购买付费能力、把仓库改为公开，或迁移正式仓库都超出 T0 封板授权范围。简单删除 Dependency Review 又会减少 PR 新增依赖的独立检查，因此必须提供仓库内可审计、强度不低于现有 T0 门禁的替代实现。

## 决策

1. 保留工作流和 Job 名 `Dependency Review` / `dependency-review`，但明确它是仓库自有实现，不冒充 GitHub Advanced Security 的原生服务。
2. PR checkout 使用完整历史，脚本校验 40 位 base/head SHA，并输出 PR 改动的 Maven、Node、Flutter、Gradle、容器、Compose、Actions 与许可证策略输入。
3. 对 PR head 重新生成 Maven 聚合 CycloneDX SBOM，使用固定且校验和验证过的 Trivy 0.72.0 阻断 HIGH/CRITICAL 漏洞。
4. 复用精确坐标/版本许可证清单；任何新增或漂移的受限组件、GPL-3.0 或 AGPL 均失败。
5. 管理后台必须 frozen install，执行 high audit 和完整许可证清单检查；非 Maven 文件系统/锁文件由 Trivy 阻断 HIGH/CRITICAL。
6. 该 PR 门禁是六项 `T0 Quality Gates` 的附加控制，不能替代服务端测试、Flutter/Kotlin/APK、密钥、IaC、Compose 或制品门禁。

## 后果

- 私有 GitHub Free 仓库可以形成可重复、可审计且实际可执行的 PR 依赖评审，但不具备 GitHub Dependency Graph 的增量 advisory UI 或 Branch Protection 强制能力。
- 团队流程必须禁止绕开 PR/CI 直推 `main`；升级 GitHub 套餐后，应通过新 ADR 评估恢复原生 Dependency Review 与 required checks，并保留自有精确许可证策略。
- 所有报告必须写明“repository-local dependency review”，不得把原生失败运行描述为成功。

## 验证方式

- PR #16 的 `dependency-review` Job 完成并为 `success`。
- 日志包含 base/head 依赖输入差异、前端 audit/许可证结果、服务端 SBOM 漏洞/许可证结果和非 Maven 锁文件结果。
- 任一步发现 HIGH/CRITICAL、GPL-3.0、AGPL-3.0 或未批准受限组件时退出码必须非 0。
- 同一 PR 的六项 T0 Quality Gates 也必须全部成功后才允许合并。
