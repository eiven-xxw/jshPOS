# T0 依赖、安全与许可证基线

基线日期：2026-08-16。

## 已完成证据

| 范围 | 验证 | 结果 |
|---|---|---|
| Vue 管理后台 | `pnpm audit --registry https://registry.npmjs.org --audit-level high` | 无已知漏洞 |
| Vue 管理后台 | ESLint、`vue-tsc`、Vitest、Vite production build | 通过 |
| Java 服务端 | Maven Wrapper `clean verify` | 37 个 reactor 模块通过 |
| Java 服务端 | CycloneDX Maven Plugin 2.9.1 | 成功生成 JSON/XML SBOM；精确组件数随安全修复提交记录 |
| Flutter POS | 锁文件、analyze、test | 通过 |
| Flutter/Kotlin 适配器 | 锁文件、analyze、3 个 Dart 测试 | 通过 |

GitHub Actions 会重新生成服务端 SBOM 和前端许可证清单，并把它们作为短期构建制品保存。正式供应链门禁使用校验和固定的 Trivy 0.72.0 阻断 high/critical 漏洞、高风险许可证、密钥与高风险 IaC 配置，并由前端门禁显式阻断 GPL-3.0、AGPL-3.0；Pull Request 额外执行 Dependency Review，Dependabot 持续检查锁定依赖。

Trivy 会把 LGPL、Classpath Exception 以及双许可证组件中的限制型选项统一标为 HIGH。鲸熵汇不使用宽泛的全局放行：`scripts/check_sbom_licenses.py` 先按 `docs/compliance/reviewed-license-allowlist.json` 校验精确坐标、版本和实际许可证集合，任何新增或版本变化都会失败；随后 Trivy 仅忽略该清单覆盖的许可证类别，继续阻断其他 HIGH/CRITICAL 许可证。双许可证组件明确选择 EPL、Apache、BSD 或 CDDL 选项；仅有 LGPL 选项的 Aviator 和 simple-http 仍是商业发布前法务/替换阻断项。

## 约束与待办

- `crypto-js` 上游包已停止维护，且 RuoYi 前端现有请求加密使用 AES-ECB。T0 不改变上游协议；在任何商业公网发布前，必须通过独立 ADR 迁移到经评审的传输/字段加密方案并完成服务端兼容切换。
- Aviator（SnailJob 任务表达式）和 simple-http（JustAuth 传递依赖）仅获 T0 技术准入；商业 V1 发布前必须替换为许可证更宽松的实现，或取得书面法务批准并落实 LGPL 通知、源代码/修改、可替换链接及适用的安装信息义务。
- MySQL Connector/J 的 Universal FOSS Exception 不被本项目预设为覆盖闭源商业分发；商业 V1 前必须取得适用的 Oracle 商业许可、改用经法务批准的兼容驱动，或调整产品许可模式。本说明不是法律意见。
- SBOM 不是漏洞扫描结论。T0 封板必须有 GitHub Actions 全量依赖扫描和 Pull Request Dependency Review 证据；high/critical 未清零不得发布。
- 设备厂商 SDK、支付 SDK 和连接器 SDK 尚未进入 T0。引入时必须记录来源、版本、校验和、许可证、数据出境与升级退出方案。
- 锁文件是可重复构建输入，禁止在同一业务变更中无审查地刷新全部依赖。
