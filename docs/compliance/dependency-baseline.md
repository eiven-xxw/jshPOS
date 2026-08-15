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

CI 会重新生成服务端 SBOM 和前端许可证清单，并把它们作为短期构建制品保存。依赖评审在 Pull Request 上阻断 high/critical 漏洞以及 GPL-3.0、AGPL-3.0 许可证；Dependabot 每周检查 Maven、npm、Pub、GitHub Actions 和 Docker 基线。

## 约束与待办

- `crypto-js` 上游包已停止维护，且 RuoYi 前端现有请求加密使用 AES-ECB。T0 不改变上游协议；在任何商业公网发布前，必须通过独立 ADR 迁移到经评审的传输/字段加密方案并完成服务端兼容切换。
- SBOM 不是漏洞扫描结论。首次连接 GitHub 后必须执行依赖评审，并在 T1 接入服务端 SCA；high/critical 未清零不得发布。
- 设备厂商 SDK、支付 SDK 和连接器 SDK 尚未进入 T0。引入时必须记录来源、版本、校验和、许可证、数据出境与升级退出方案。
- 锁文件是可重复构建输入，禁止在同一业务变更中无审查地刷新全部依赖。
