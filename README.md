# 鲸熵汇收银系统

连接器型、多租户、离线可营业的商业收银经营平台。

当前阶段：**T0 技术基线**。本阶段只建立需求治理、架构决策、工程骨架、开发环境和持续集成，不实现正式交易业务。

## 工程

- `server/`：RuoYi-Vue-Plus 5.6.2 模块化单体服务端。
- `admin-web/`：配套 Vue 3 + TypeScript 管理后台。
- `pos-flutter/`：Flutter Android POS。
- `packages/pos_device_adapter/`：Flutter/Kotlin 设备适配契约。
- `contracts/`：OpenAPI、事件和连接器契约。
- `infra/`：本地依赖与部署基线。
- `.github/workflows/`：GitHub Actions 正式 T0 质量门禁。
- `ci/codeup/`：Codeup / 云效 Flow 镜像与灾备参考配置。
- `tests/`：契约、端到端、硬件和故障测试入口。
- `docs/governance/`：RTM、变更控制和研发证据。
- `docs/adr/`：架构决策记录。

## 开始前

1. 阅读根目录 `AGENTS.md`。
2. 阅读 `docs/governance/development-governance.md`。
3. 从 `docs/governance/rtm.csv` 选择已准入需求。
4. 不得把示例口令用于共享或生产环境。

## T0 验证

Windows：

```powershell
$env:JSH_POS_PYTHON = 'C:\path\to\python.exe'
$env:JSH_POS_FLUTTER = 'C:\path\to\flutter.bat'
pwsh ./scripts/verify-t0.ps1
```

完整门禁需要 JDK 21、Node 24、pnpm 10.33、Flutter 3.47、Android SDK 和 Docker Compose v2。只做不含 APK/Compose 的本地复验时可显式使用 `-SkipAndroidBuild -SkipInfrastructure`；这两项仍必须在 CI 通过后才能签署 T0。各工程也可以独立验证；所需版本见 `VERSION_BASELINE.md`。

正式代码仓库为 GitHub `eiven-xxw/jshPOS`，GitHub Actions 是 T0 验收与制品权威入口。Codeup/云效 Flow 仅作可选镜像与灾备参考，详见 `docs/adr/ADR-013-github-primary-repository-ci.md`。
