# T0 本地开发环境

## 固定工具链

| 工具 | 版本/范围 | 校验 |
|---|---|---|
| JDK | 21 LTS | `java -version`；服务端语言级仍为 17 |
| Maven | 3.9.9 | 只使用 `server/mvnw` 或 `mvnw.cmd` |
| Node.js | 24 LTS | `node --version` |
| pnpm | 10.33.0 | `pnpm --version` |
| Python | 3.12+ | 仅用于治理校验脚本 |
| Flutter / Dart | 3.47.0 / 3.13.0 | `flutter --version` |
| Android SDK | API 36 + 对应 Build Tools | `flutter doctor -v` |
| Docker | Compose v2 | `docker compose version` |

版本升级必须先更新 `VERSION_BASELINE.md`、ADR、锁文件和回归证据。

## 首次准备

1. 配置 JDK 21 的 `JAVA_HOME`，不要把 IDE 自带的未知版本当成团队基线。
2. 安装 Node 24 和 pnpm 10.33；在 `admin-web` 执行 `pnpm install --frozen-lockfile`。
3. 安装 Flutter 3.47.0 stable 与 Android SDK，执行 `flutter doctor -v` 并接受 Android SDK 许可证。
4. 复制 `infra/compose/.env.example` 为本地 `.env`，立即替换示例口令；`.env` 已被 Git 忽略。
5. 设置 `JSH_POS_PYTHON` 和 `JSH_POS_FLUTTER`，让统一门禁不依赖系统 PATH 的偶然状态。

## 常用命令

```powershell
# 治理与全部本地门禁
pwsh ./scripts/verify-t0.ps1

# 服务端
cd server
./mvnw.cmd -DskipTests=false clean verify

# Vue 后台
cd admin-web
pnpm install --frozen-lockfile
pnpm lint:eslint
pnpm typecheck
pnpm test:unit
pnpm build:prod

# Flutter POS
cd pos-flutter
flutter pub get --enforce-lockfile
flutter analyze --fatal-infos
flutter test
flutter build apk --debug
```

`-SkipAndroidBuild` 和 `-SkipInfrastructure` 只用于明确记录本机缺少工具的局部复验；带 SKIP 的结果不能替代 CI 绿灯。

## 端口与本地依赖

- MySQL: `3306`
- Redis: `6379`
- 管理后台开发服务器：以 `admin-web/.env.development` 为准
- 服务端端口：以上游配置和本地 profile 为准

不得使用共享测试或生产口令启动本地容器，不得把真实顾客、支付或会员数据复制到开发环境。
