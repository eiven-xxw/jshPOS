# Codeup / 云效 Flow T0 镜像配置

`t0-flow.yml` 是鲸熵汇收银系统在 Codeup/云效 Flow 上的镜像与灾备参考。根据 ADR-013，GitHub Actions 已是正式 T0 验收入口；Flow 运行不得单独把 RTM 更新为 `ACCEPTED`。Codeup 不会像 GitHub 一样自动识别仓库内的 CI 文件，如需灾备演练，必须在 Flow 控制台创建“YAML 化编排”流水线，并把本文件同步到编辑器。

## 绑定方式

1. 从 Codeup 仓库页面选择“创建流水线”，确保代码源自动绑定到本仓库 `main`。
2. 选择“空模板”和“YAML 化编排”。
3. 将 `ci/codeup/t0-flow.yml` 的完整内容写入 Flow YAML 编辑器。
4. 使用控制台“校验”，不得忽略动态资源或步骤错误。
5. 保存并运行；开启 Codeup `main` 的代码提交触发。
6. 失败时修复仓库中的权威文件，再同步 Flow；禁止只在控制台产生不可追溯差异。

## 门禁与制品

| Job | 阻断内容 | 主要制品 |
|---|---|---|
| 治理与契约 | 目录、RTM、OpenAPI、JSON Schema | 日志 |
| 服务端与 SBOM | JDK/Maven 构建测试、CycloneDX | JSON/XML SBOM |
| Vue 管理后台 | 锁文件、漏洞审计、lint、类型、测试、构建、许可证 | 许可证清单 |
| Flutter 与 Android | 固定 Flutter、Dart 测试、Kotlin 单测、APK 编译 | debug APK |
| 基础设施配置 | Docker Compose v2 解析 | 日志 |
| 供应链安全 | Trivy 高危/严重漏洞、许可证、密钥、IaC | 安全扫描 SBOM |

Flow 控制台中的流水线 ID、运行编号、运行 URL、commit SHA 和制品链接必须回填到 `docs/evidence/`。没有真实运行证据时，RTM 不得更新为 `ACCEPTED`。

## 安全约束

- 构建容器固定到不可变镜像摘要。
- Flutter 基础镜像固定摘要后，再切换到官方 `3.47.0` tag，并核对 commit `4cf24164269a5ebf0c16a028a00727d0e77bbb05`。
- Trivy 固定 `0.72.0`，下载包必须通过内置 SHA-256 校验。
- 流水线不含部署步骤、生产密钥、支付密钥或生产数据。
- Flow 服务连接只授予读取本仓库所需权限；不得给 T0 流水线生产部署权限。
