# 鲸熵汇收银系统

连接器型、多租户、离线可营业的商业收银经营平台。

当前阶段：**T2 Gate 7B / Sprint S20-B：T2-EXG-001 独立 VERIFIED，等待项目发起人确认**。基础换货只表达“原单退货退款 + 新销售 + 只追加关联”；`T2-PAY-004` 与 Gate 7C 继续 `DRAFT`，未经再次确认不得编码。支付沙箱、真实硬件、真实打印和设计伙伴继续 `BLOCKED`，完整 Alpha 与发布继续 `DRAFT`；不得宣称可试点、可生产或可商用。

## 工程

- `server/`：RuoYi-Vue-Plus 5.6.2 模块化单体服务端。
- `admin-web/`：配套 Vue 3 + TypeScript 管理后台。
- `pos-flutter/`：Flutter Android POS。
- `packages/pos_device_adapter/`：Flutter/Kotlin 设备适配契约。
- `contracts/`：OpenAPI、事件和连接器契约。
- `infra/`：本地依赖与部署基线。
- `.github/workflows/`：GitHub Actions T0—T2 各阶段正式质量门禁。
- `ci/codeup/`：Codeup / 云效 Flow 镜像与灾备参考配置。
- `tests/`：契约、端到端、硬件和故障测试入口。
- `docs/governance/`：RTM、[研发治理基线](docs/governance/development-governance.md)、[后端开发规范](docs/governance/backend-development-standards.md)、[模型/持久化/数据库注释规范](docs/governance/model-persistence-database-comment-standards.md)、变更控制和研发证据。
- `docs/adr/`：[架构决策目录](docs/adr/README.md)。

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
