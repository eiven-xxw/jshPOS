# T2 Gate 6H / Sprint S18 周门禁暨内部发布候选报告

## 1. 评审结论

建议 `CONDITIONAL PASS`，等待项目发起人确认。`T2-UX-001 → T2-PERF-001 → T2-OPS-001 → T2-RC-001` 已严格串行达到 `VERIFIED`。证据上限为 `INTERNAL_RELEASE_CANDIDATE`，不得解释为完整 Alpha、实机、支付沙箱、试点、生产或商业 SLA。

## 2. 代码与候选身份

- 工作分支：`codex/t2-gate6h-sprint18-internal-release-candidate`
- Gate 6H 起点：`c2db49fb47db5fe30fe01515b60ee6f054b214e3`
- 候选实现与修复提交：`96a52b34884234ee5694b855a6f5e162b1f45017`
- 内部候选 ID：`jshpos-internal-rc-96a52b348842-32469509542`
- GitHub Run：[32469509542](https://github.com/eiven-xxw/jshPOS/actions/runs/32469509542)
- 内部候选 Artifact：`9442421137`，GitHub Artifact SHA-256：`4c7f27c72597bffb05f6c91e4338a0eb35d62bf984c18163ee1583d1d1a0bd43`

候选内含服务端 JAR、Vue 生产构建、Android debug APK、同 Run 证据清单、P0/P1 缺陷账、变更说明、`SHA256SUMS`、候选清单、临时 Ed25519 签名及公钥。签名私钥未上传且已在 Job 内删除。

## 3. 十二项同 Run 门禁

| Job | 结果 | 核验重点 |
| --- | --- | --- |
| governance | PASS | RTM/ADR/契约/串行状态/外部零执行 |
| ux | PASS | 三业态、Vue、Flutter、组件与页面状态 |
| performance | PASS | 七维内部合成趋势与守恒 |
| operations | PASS | Compose、健康、诊断、恢复、回退 |
| full-server | PASS | 模块化单体 clean verify、覆盖率、SBOM |
| full-web | PASS | 构建、lint、typecheck、测试、审计、许可证 |
| full-pos-linux | PASS | Flutter 全量、覆盖率、SQLite、Kotlin、APK、SBOM |
| full-pos-windows | PASS | Windows Flutter 全量回归 |
| full-mysql | PASS | 空库全部前向迁移 |
| runtime-stack | PASS | Server/Web/MySQL/Redis/Flutter SQLite 同窗闭环 |
| security | PASS | 高危漏洞、Secret、IaC、许可证策略 |
| internal-release-candidate | PASS | 同 Run 汇总、摘要、签名、P0/P1、边界 |

内部候选开放 P0=`0`、P1=`0`；该结论只适用于当前合成与软件执行范围。

## 4. 失败与修复账

Run [32468077141](https://github.com/eiven-xxw/jshPOS/actions/runs/32468077141) 中十项成功，但 `runtime-stack` 的健康探针因遗漏合成只读健康身份收到 `401`，最终候选被正确跳过。提交 `96a52b3` 恢复既有健康身份契约，未开放端点、忽略 401、关闭探针、删测或降低门禁；Run `32469509542` 完整重跑后通过。

性能阶段更早的五个失败 Run 与修复原因继续保留在《T2-PERF-001 独立验证报告》，没有自动重跑掩盖 Flaky。

## 5. 明确保留状态

- `T2-PAY-002`、`T2-HWD-001`、`T2-PAR-001`、`T2-PRN-001`：`BLOCKED`；
- `T2-UAT-001`、`T2-REL-001` 及全部 V1 汇总项：`DRAFT`；
- `T2-LIC-001`、`T2-JSH-001`：`DEFERRED`；
- Provider 网络、真实资金、真实终端命令、伙伴联系、现场试点、完整 Alpha 与生产部署：`0`。

## 6. Go/No-Go

内部工程候选：`GO_INTERNAL_RC`。完整 Alpha、现场试点、生产部署、商业发布：`NO-GO`。支付沙箱、真实硬件/打印、设计伙伴与商业许可证任一 P0 未完成独立解阻和发起人确认前，不得提升证据等级。
