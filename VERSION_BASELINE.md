# T0 工具链与上游版本基线

基线日期：2026-08-16。升级必须通过 ADR 和兼容性回归，不跟随上游主分支自动升级。

| 组件 | 固定基线 | 说明 |
|---|---|---|
| RuoYi-Vue-Plus | tag `v5.6.2`, commit `8136a0191a2258c0e1b36a8146a1c5ebc070c139` | MIT；内部快照位于 `server/` |
| plus-ui | tag `v5.6.2-v2.6.2`, commit `d0d451967676707021b9857df529c395b27e90a7` | MIT；内部快照位于 `admin-web/` |
| Spring Boot | 3.5.15 | 跟随已固定 RuoYi 5.6.2 |
| Java 编译级别 | 17 | 遵循 RuoYi 5.6.x；在 JDK 21 上构建，升为语言级 21 需 T1 全量验证 |
| JDK | 21 LTS | CI 使用 Temurin 21；本机可使用受支持的 Microsoft/OpenJDK 21 |
| Maven | 3.9.9 | 通过 Maven Wrapper 固定 |
| Node.js | 24 LTS | CI 固定 24；本地允许同一 LTS 主版本 |
| pnpm | 10.33.0 | 由 Corepack 固定 |
| Vue | 3.5.30 | 跟随 plus-ui 快照 |
| Flutter | 3.47.0 stable | 固定季度评估升级 |
| Dart | 3.13.0 | 随 Flutter 3.47.0 |
| Android 新认证基线 | Android 11+ / ARM64 | Android 9/10 仅兼容已认证存量机型 |
| MySQL | 8.4 LTS | InnoDB、utf8mb4、严格 SQL 模式 |
| Redis | 7.4 系列 | 仅缓存/协调，不作为交易事实源 |
| 正式 CI | GitHub Actions `ubuntu-24.04` 托管 runner | 实际 image 版本记录在每次 run 日志；Action 固定完整 SHA |
| 云效 Flow Python 镜像 | Python 3.12.11 slim-bookworm + digest | 治理与契约门禁 |
| 云效 Flow Maven 镜像 | Maven 3.9.9 + Temurin 21 + digest | 服务端与供应链门禁 |
| 云效 Flow Node 镜像 | Node 24.9.0 bookworm-slim + digest | Vue 管理后台门禁 |
| 云效 Flow Flutter 基础镜像 | Cirrus Flutter stable digest（内含 Android SDK） | 运行时强制切换并核对 Flutter 3.47.0 commit |
| 云效 Flow Docker CLI | Docker CLI/Compose 27.5.1 + digest | Compose 配置解析，不连接生产 Docker daemon |
| Trivy | 0.72.0 + SHA-256 `bbb64b9695866ce4a7a8f5c9592002c5961cab378577fa3f8a040df362b9b2ea` | 漏洞、许可证、密钥与 IaC 门禁 |

## T0 安全覆盖版本

| 组件 | T0 固定/解析版本 | 治理原因 |
|---|---:|---|
| fastjson2 1.x API 兼容制品 | 2.0.61 | 替代存在已知高危且无修复版本的 fastjson 1.2.83；兼容测试锁定当前使用面 |
| Netty BOM | 4.1.136.Final | 修复 T0 SBOM 发现的 HIGH 漏洞 |
| Apache HttpCore | 5.4.3 | 修复 T0 SBOM 发现的 HIGH 漏洞 |
| Bouncy Castle | 1.85 | 固定在本次漏洞修复线以上 |
| PostgreSQL JDBC | 42.7.12 | 修复 T0 SBOM 发现的 HIGH 漏洞 |
| Apache Fory | 1.1.0 | 修复 SnailJob 传递依赖漏洞；未使用的 Redis 直接依赖已删除 |
| Aviator | 5.4.4 | 使用含沙箱安全修复的版本；商业发布许可证事项仍需关闭 |
| Java 容器运行身份 | UID/GID `10001:10001` | 服务端、监控端、SnailJob 均禁止 root 运行 |

T0 代码候选 `9fd73f04e1b1304071ad5bfd4259fb84679d926c` 已通过 GitHub Actions #34 全量门禁。精确许可证处置见 `docs/compliance/reviewed-license-allowlist.json` 和 ADR-015；这不是商业分发法律意见。

## 上游更新策略

- 仅从正式 tag 引入，记录 tag、commit、许可证和差异。
- 6.x 大版本必须单独完成迁移评估；不得在 T0/T1 中无门禁替换 5.6.2。
- 安全修复可通过补丁优先回移；升级仍需构建、租户隔离和回归证据。
