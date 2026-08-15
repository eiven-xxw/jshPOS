# ADR-013：GitHub 作为正式代码仓库与 T0 CI 执行平台

- 状态：Accepted
- 日期：2026-08-16

## 背景

T0 需要稳定执行 Maven、Node/pnpm、Flutter/Android、Docker Compose、CycloneDX、Trivy 和依赖评审。Codeup/云效 Flow 已完成可行性接入，但公共构建环境拉取 Docker、Flutter 与安全数据库时受网络和镜像兼容性影响，难以形成可重复的商业封板证据。

## 决策

GitHub 仓库 `eiven-xxw/jshPOS` 作为正式代码事实源，`main` 为 T0 基线分支；GitHub Actions 是正式质量门禁与制品来源，Dependabot 和 Dependency Review 是依赖治理入口。所有第三方 Action 固定到完整 commit SHA，工作流最小权限运行，APK、SBOM 和许可证清单作为限期保留制品。

Codeup 仓库保留为可选镜像，云效 Flow YAML 保留为灾备/迁移参考，不再作为 T0 验收的权威运行证据。镜像不得反向覆盖 GitHub `main`，也不得拥有生产部署权限。

## 后果

- T0 验收报告、RTM 和证据必须引用 GitHub commit、Actions run、Job 与制品链接。
- `main` 的正式提交必须通过治理、服务端、Vue、Flutter/Android、Compose、供应链安全门禁；Pull Request 额外通过 Dependency Review。
- 仓库切换不改变 T0 功能边界，不引入订单、支付、库存、促销或生产部署逻辑。
- 若未来恢复 Codeup 为主仓库，必须新增 ADR、完成等价门禁验证并重新封板。
