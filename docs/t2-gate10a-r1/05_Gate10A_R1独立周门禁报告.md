# Gate 10A-R1 独立周门禁报告

## 当前结论

`IN_PROGRESS / AWAITING_CLEAN_GITHUB_CI`。

本报告将在新的候选提交完成完整 GitHub CI 后回填精确 commit、Run、Job、Artifact、
SHA-256、四栈 clean-run 结果和最终 Go/No-Go。项目发起人确认前，三个 P2 Finding
不得关闭，R2 不得启动。

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

## 待完成门禁

| Finding | 当前状态 | 关闭前条件 |
|---|---|---|
| G10A-CI-P2-001 | IN_PROGRESS | Node 24 工作流在 GitHub 干净执行器全绿 |
| G10A-DEP-P2-001 | IN_PROGRESS | 四栈串行 clean run、安全/许可证与兼容证据全绿 |
| G10A-SUP-P2-001 | IN_PROGRESS | Action 账、SBOM、制品摘要和完整 Gate 9C 回归全绿 |

## 证据边界

最高允许结论为 `INTERNAL_CI_DEPENDENCY_SUPPLY_CHAIN_VERIFIED_CANDIDATE`；不代表
支付沙箱、真实资金、真实设备/外设、完整 Alpha、现场试点、生产、商业验收或商业 SLA。
