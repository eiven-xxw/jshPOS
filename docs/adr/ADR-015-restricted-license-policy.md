# ADR-015：受限许可证的精确组件准入策略

- 状态：Accepted
- 日期：2026-08-16

## 背景

Trivy 0.72.0 对 CycloneDX SBOM 执行标准包许可证扫描时，将 12 个组件标为 HIGH。进一步对全部 SBOM 许可证做自有校验后确认，多数组件同时提供 EPL、Apache、BSD 或 CDDL 等可选许可证；另有 Aviator、simple-http 和未使用的 MariaDB 数据源仅提供 LGPL 选项，MySQL Connector/J 则采用 GPLv2 + Universal FOSS Exception。

简单关闭许可证扫描会失去新增依赖的阻断能力；按许可证名称做永久全局忽略又会让未来未经审查的 LGPL 依赖静默进入。因此需要组件级、版本级和许可证集合级的准入策略。

## 决策

1. 删除 T0 未使用的 SnailJob MariaDB 数据源；鲸熵汇 T0 数据库认证基线仍为 MySQL 8.4。
2. Aviator 固定到包含最新沙箱安全修复的 5.4.4；其 LGPL 义务和替换计划继续登记。
3. 使用 `reviewed-license-allowlist.json` 记录精确坐标、版本、受限许可证、选择的替代许可证和商业发布状态。
4. CI 先运行自有校验脚本：任何新增受限组件、版本漂移、许可证集合变化、GPL-3.0 或 AGPL 均失败；校验成功后，Trivy 只忽略已由精确清单覆盖的许可证类别，并继续阻断其他 HIGH/CRITICAL 许可证。
5. 双许可证组件明确选择 EPL/Apache/BSD/CDDL 选项。Aviator 与 simple-http 仅获 T0 技术准入；MySQL Connector/J 也不被假设可直接闭源分发。商业 V1 前必须完成替换、适用商业许可或书面法务意见及履约措施。

## 后果

- 门禁从“按许可证类别粗粒度阻断”提升为“Trivy 分类 + 精确组件清单”的双层策略；新增同类许可证依赖也不能自动通过。
- T0 可以保留 RuoYi/SnailJob/JustAuth 工程骨架并持续构建，但不得把 T0 通过表述为许可证法律意见或商业发布许可。
- 升级任何已批准组件时，必须重新核对许可证并更新清单、第三方声明和测试证据。
- 商业 V1 的 Go/No-Go 必须关闭 Aviator、simple-http 和 MySQL Connector/J 的“替换/商业许可/法务批准”事项。

## 验证方式

- CycloneDX SBOM 中不再包含 MariaDB Connector/J。
- 自有脚本报告全部受限许可证组件均与精确清单一致，无未批准 GPL/AGPL/受限组件。
- Trivy SBOM 许可证 HIGH/CRITICAL 门禁通过，且没有使用 `--exit-code 0`、`continue-on-error` 或降低 severity。
- Pull Request Dependency Review 继续拒绝 GPL-3.0、AGPL-3.0 和 high severity 依赖。
