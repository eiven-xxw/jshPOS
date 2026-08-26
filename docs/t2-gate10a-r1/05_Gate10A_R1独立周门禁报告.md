# Gate 10A-R1 独立周门禁报告

## 当前结论

`CONDITIONAL PASS / VERIFIED_AWAITING_SPONSOR_CONFIRMATION`。

实现候选 `48c6b52664fa7f5de98db4c47750587f527b08d2` 的 GitHub Actions Run
[`32942796926`](https://github.com/eiven-xxw/jshPOS/actions/runs/32942796926) 在同一提交上
完成全部门禁，10 个 Job 全绿、10 个 Artifact 可见，总耗时 15 分 44 秒。三个 P2 Finding
只更新为 `VERIFIED_AWAITING_SPONSOR_CONFIRMATION`；项目发起人确认前不得关闭，R2 不得启动。

## 已完成的静态整改

- ADR-073 已更新为 `Accepted`；
- Gate 9C annotated tag 已创建、推送并完成对象与 peeled commit 核验；
- 全仓远程 Action 已纳入单一版本账并固定为不可变 SHA；
- JavaScript Action 已统一到 Node 24 运行时，Flutter Action 保持 composite；
- Maven、pnpm、Flutter Pub、Kotlin/Gradle 应用依赖与锁文件保持不变；
- 新增 Ubuntu/Windows 治理、四栈串行 clean run、安全、Gate 9C 回归与证据聚合门禁；
- 已发布迁移变更为 0，新增业务、外部网络和设备执行为 0。

首轮候选 Run `32940429973` 在治理阶段失败关闭，定位为工作区 CRLF/LF 参与依赖摘要
导致的跨平台非确定性；失败历史已冻结为 `G10A-R1-CI-001`。修复只把摘要输入改为
Git commit blob 规范字节，不改变任何应用依赖、业务代码或门禁阈值，并从新提交全量复跑。

第二轮候选 Run `32940754079` 已通过治理、Maven、pnpm 及 Flutter Ubuntu/Windows，
随后因脚本错误假定设备适配 example 中被 `.gitignore` 排除的生成式 `gradlew` 已提交而
失败关闭。该失败冻结为 `G10A-R1-KOT-001`；修复改为使用正式 POS 已跟踪的 Gradle
wrapper 发现并编译插件工程、执行 Kotlin 单测和构建包含插件的 APK，不新增或提交生成文件。

## 完整门禁结果

| Finding | 当前状态 | 已验证证据 |
|---|---|---|
| G10A-CI-P2-001 | VERIFIED_AWAITING_SPONSOR_CONFIRMATION | Ubuntu/Windows 治理、Node 24 与不可变 Action SHA 全绿 |
| G10A-DEP-P2-001 | VERIFIED_AWAITING_SPONSOR_CONFIRMATION | Maven→pnpm→Flutter 双平台→Kotlin/Android 串行 clean run 全绿，应用依赖及锁文件未变 |
| G10A-SUP-P2-001 | VERIFIED_AWAITING_SPONSOR_CONFIRMATION | Action 机器账、安全/SBOM/许可证、Gate 9C 回归及证据聚合全绿 |

| Job | 结论 | Job ID / 摘要 |
|---|---|---|
| governance-ubuntu | SUCCESS | `98097112363` |
| governance-windows | SUCCESS | `98097112339` |
| maven | SUCCESS | `98097216807` |
| pnpm | SUCCESS | `98097987538` |
| flutter (ubuntu/windows) | SUCCESS / SUCCESS | 双平台依赖、分析、测试与制品均通过 |
| kotlin-android | SUCCESS | `98099408889` |
| security | SUCCESS | `98100730923` |
| gate9c-regression | SUCCESS | `98100822027` |
| evidence | SUCCESS | `98100894954` |

## 范围与回归结论

- 77 个工作流的第三方 Action 引用均由机器账固定到 40 位 SHA；原有门禁语义未删减；
- Maven、pnpm、Flutter Pub、Kotlin/Gradle 的应用依赖清单与锁文件相对起点保持不变；
- Server、Vue、Flutter、Kotlin/Android 生产业务代码变更为 0；
- 已发布 MySQL/SQLite 迁移变更为 0，当前 87 个 MySQL 迁移保持不变；
- Gate 9C 的 88 项 ACCEPTED、300 API、26 页面、22 Owner 与三业态/SaaS 回归通过；
- 外部状态、Provider 网络、真实资金、设备/外设、伙伴现场、完整 Alpha 和生产执行均未改变。

## Go/No-Go 建议

建议项目发起人接受 Gate 10A-R1 `CONDITIONAL PASS`，并将三项 Finding 确认为
`CLOSED_IN_GATE10A_R1`。该确认只关闭内部 CI、依赖快照和供应链治理问题；不会自动准入 R2，
也不会提升任何外部证据等级。

## 证据边界

最高允许结论为 `INTERNAL_CI_DEPENDENCY_SUPPLY_CHAIN_VERIFIED_CANDIDATE`；不代表
支付沙箱、真实资金、真实设备/外设、完整 Alpha、现场试点、生产、商业验收或商业 SLA。
