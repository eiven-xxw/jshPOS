# T0 依赖、安全与许可证基线

基线日期：2026-08-15。

## 已完成证据

| 范围 | 验证 | 结果 |
|---|---|---|
| Vue 管理后台 | `pnpm audit --registry https://registry.npmjs.org --audit-level high` | 无已知漏洞 |
| Vue 管理后台 | ESLint、`vue-tsc`、Vitest、Vite production build | 通过 |
| Java 服务端 | Maven Wrapper `clean verify` | 37 个 reactor 模块通过 |
| Java 服务端 | CycloneDX Maven Plugin 2.9.1 | 成功生成 415 组件的 JSON/XML SBOM |
| Flutter POS | 锁文件、analyze、test | 通过 |
| Flutter/Kotlin 适配器 | 锁文件、analyze、3 个 Dart 测试 | 通过 |

GitHub Actions 会重新生成服务端 SBOM 和前端许可证清单，并把它们作为短期构建制品保存。正式供应链门禁使用校验和固定的 Trivy 0.72.0 阻断 high/critical 漏洞、高风险许可证、密钥与高风险 IaC 配置，并由前端门禁显式阻断 GPL-3.0、AGPL-3.0；Pull Request 额外执行 Dependency Review，Dependabot 持续检查锁定依赖。

## 约束与待办

- `crypto-js` 上游包已停止维护，且 RuoYi 前端现有请求加密使用 AES-ECB。T0 不改变上游协议；在任何商业公网发布前，必须通过独立 ADR 迁移到经评审的传输/字段加密方案并完成服务端兼容切换。
- SBOM 不是漏洞扫描结论。T0 封板必须有 GitHub Actions 全量依赖扫描和 Pull Request Dependency Review 证据；high/critical 未清零不得发布。
- 设备厂商 SDK、支付 SDK 和连接器 SDK 尚未进入 T0。引入时必须记录来源、版本、校验和、许可证、数据出境与升级退出方案。
- 锁文件是可重复构建输入，禁止在同一业务变更中无审查地刷新全部依赖。
