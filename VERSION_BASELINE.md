# T0 工具链与上游版本基线

基线日期：2026-08-15。升级必须通过 ADR 和兼容性回归，不跟随上游主分支自动升级。

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

## 上游更新策略

- 仅从正式 tag 引入，记录 tag、commit、许可证和差异。
- 6.x 大版本必须单独完成迁移评估；不得在 T0/T1 中无门禁替换 5.6.2。
- 安全修复可通过补丁优先回移；升级仍需构建、租户隔离和回归证据。
