# T1 Week 4 周门禁暨 T1 退出评审准备报告

> 文档编号：JSH-POS-T1-W4-001  
> 日期：2026-08-16  
> 唯一技术基线：annotated tag `t0-baseline-2026-08-16`  
> Week 4 实现提交：`a006c9b032f13fc5dc32d13021c0e321747b3756`  
> 跨平台修复提交：`8354ac55bf66304c4a911ea07f2b2a949589a5bc`  
> Week 4 候选门禁：[T1 Week 4 Closure Reproducibility Gates #31925516509](https://github.com/eiven-xxw/jshPOS/actions/runs/31925516509)  
> 结论：`T1 INTERNAL STATIC/FAKE CONDITIONAL PASS / EXIT REVIEW READY / AWAITING SP CONFIRMATION`

## 1. 管理结论

项目发起人批准的 T1 Week 4 收口与退出评审准备工作已经完成。Week 1—3 的全部内部 `STATIC/FAKE` 自动化已在 GitHub Ubuntu 与 Windows 两类干净执行器上重新执行；4,387 条断言、381,371 次核心迭代、76 个输入文件、固定 seed 总账和归一化证据摘要在两平台一致。Week 4 六个 Job、Week 1—3 回归工作流均通过，未跳过测试、未使用 `continue-on-error`、未减少 seed 或断言，也未降低安全阈值。

本阶段只修改治理、PoC 契约、隔离合成探针、测试、CI 和退出材料。没有修改 `server`、`admin-web`、`pos-flutter`、生产设备适配或基础设施，没有创建正式订单、支付、退款、库存、促销业务表、API 或页面；没有真实支付网络调用、生产凭据、真实商户数据、未脱敏 PII、真实设备或外设。

T1 当前只能判定“内部 `STATIC/FAKE` 风险基线条件通过并具备退出评审条件”。该结论不表示 T1 所有需求完成，不是 `SANDBOX`、`REAL_DEVICE`、物理断电、门店试点或商业验收，不授权直接进入 T2 业务编码。7 项外部实证继续 `BLOCKED`，2 项继续 `DEFERRED`，9 项内部探针继续 `IN_PROGRESS`；本报告等待项目发起人确认。

## 2. 授权范围与边界核对

| 检查项 | 结果 | 证据/限制 |
|---|---|---|
| T0 基线可追溯 | PASS | `t0-baseline-2026-08-16` 是当前分支祖先；未修改或移动 T0 tag |
| Week 1—3 双平台复跑 | PASS | Ubuntu/Windows 均从干净 checkout 运行同一命令和固定输入 |
| 临时捷径与未使用夹具 | PASS | 18 个夹具全部被验证脚本引用；捷径标记 0 |
| Secret/PII/网络入口 | PASS | 扫描 41 个 PoC/治理文件；Secret 0、PII 0、网络客户端 0、凭据读取 0 |
| 非合成数据 | PASS | 非合成表/输入 0；只使用虚构租户、Provider 和合成事件 |
| 依赖与门禁阈值 | PASS | T1 新增运行时第三方依赖 0；依赖清单变更 0；现有 HIGH/CRITICAL 阈值未降低 |
| 正式业务边界 | PASS | 生产工程与正式订单、支付、库存、促销能力未修改 |
| 外部实证边界 | PASS | 未创建 SANDBOX/REAL_DEVICE/PILOT 绿色占位，阻断状态未解除 |

## 3. 首轮跨平台缺陷与处理

Week 4 首轮候选运行 [#31924509774](https://github.com/eiven-xxw/jshPOS/actions/runs/31924509774) 中，Ubuntu 与 Windows 各自的 Week 1—3 探针全部通过，但最终比较门禁失败。原因是 Windows checkout 按平台规则把 JSON 夹具换行为 CRLF，导致原始 `fixtureDigest` 与 Ubuntu 不一致。

该问题没有被忽略，也没有通过排除夹具、放宽摘要或减少覆盖来规避。项目执行了以下纠正：

1. 新增 `.gitattributes`，对 T1 PoC、契约和验证脚本明确执行 LF 规范化；
2. 扩展 Week 4 校验，阻断缺失 LF 属性的提交；
3. 在 `CR-T1-008` 中登记缺陷、原因和修复边界；
4. 从头重新执行 Week 1—4 全部自动化。

修复后的运行 #31925516509 首次执行即全部通过。该过程证明跨平台比较门禁确实能够发现可重复性缺陷，失败运行和修复记录均保留，不将失败覆盖为绿色。

## 4. 双平台重复验证结果

### 4.1 总量与一致性

| 指标 | Ubuntu | Windows | 比较结论 |
|---|---:|---:|---|
| 输入文件数 | 76 | 76 | 一致 |
| 输入树摘要 | `8043acb1d36773e9522d53eca107b299ebc3946243017874b80835f995e5fdd4` | 同左 | 一致 |
| Week 1 断言 | 83 | 83 | 一致 |
| Week 2 断言 | 2,879 | 2,879 | 一致 |
| Week 3 断言 | 1,425 | 1,425 | 一致 |
| 总断言 | **4,387** | **4,387** | 一致 |
| 总核心迭代 | **381,371** | **381,371** | 一致 |
| 观察到失败 seed | 0 | 0 | 一致 |
| 未跟踪失败 seed | 0 | 0 | 一致 |
| 归一化证据 SHA-256 | `519e831805f0ce2532a2d84153d39c9008855f4a909ac07201d2b7b830602d5a` | 同左 | `reproducible=true` |

失败 seed 总账摘要为 `ecccfb0850955cf6883f3bbebb1c01b9305f7717a5e26e00265837bdfd5809e1`。总账中 `observedFailedSeeds=[]`、`fixedFailedSeeds=[]`、`untracked=0`，表示本轮没有观察到失败 seed；不表示未来故障不存在。

### 4.2 执行环境与耗时

| 环境 | 版本 | Week 1 | Week 2 | Week 3 | 探针合计 |
|---|---|---:|---:|---:|---:|
| GitHub Ubuntu / Linux | Python 3.12.13 | 约 0.039 秒 | 约 47.447 秒 | 约 443.831 秒 | 约 491.317 秒 |
| GitHub Windows Server 2025 | Python 3.12.10 | 约 0.083 秒 | 约 104.131 秒 | 约 898.179 秒 | 约 1,002.393 秒 |

两平台原始证据包含执行时间、性能和平台换行等环境差异，因此原始文件摘要允许不同；比较器只剔除已明确定义的非确定性元数据，业务结果、断言、seed、输入树和事实摘要不得不同。归一化证据字节完全一致。

## 5. 七类 PoC 退出结论

| PoC | 最高证据 | 已验证结论 | 尚未验证 | 状态/风险 |
|---|---|---|---|---|
| Android 设备与外设 | `FAKE` | 打印、扫码、称重、钱箱、客显统一能力/错误/超时契约可重复 | 厂商 SDK、固件、主机及外设实机、长稳与恢复 | 合同 `IN_PROGRESS`；实机项 `BLOCKED` / P0-P1 |
| 支付不确定性 | `FAKE` | 五家 Fake 的支付/退款 `UNKNOWN`、查询/回调收敛、幂等和合成对账可重复 | 合作方沙箱、终端、真实回调/退款/对账、真实网络 | 合同 `IN_PROGRESS`；沙箱 `BLOCKED` / P0 |
| 本地崩溃恢复 | `FAKE` | SQLite 同事务、进程 kill、Outbox 出队/ACK 丢失/积压恢复可重复 | Android 文件系统、真实闪存、物理断电、正式本地表 | `IN_PROGRESS` / P0 |
| 同步幂等与乱序 | `FAKE` | Inbox/Outbox 在重复、乱序、ACK 丢失和重启叠加时最终收敛 | 正式服务端 Inbox、真实网络、生产容量与监控 | `IN_PROGRESS` / P0 |
| 租户隔离 | `FAKE` | 两个虚构租户的 7 类入口、28 个向量、84 次攻击回归无泄漏 | 正式 RuoYi Mapper、SQL、任务、缓存、存储和渗透测试 | `IN_PROGRESS` / P0 |
| 商品/价格数据包 | `FAKE` | 10k/100k、损坏拒收、续传、重放拒绝、跨租户拒绝和原子切换 | 主认证 Android 性能、生产签名、正式商品/价格数据 | `IN_PROGRESS` / P1 |
| App/Schema 升级 | `FAKE` | 迁移失败、应用回退、兼容窗口、待同步阻断和前向修复可重复 | APK 安装、厂商静默升级、实机磁盘/进程和灰度发布 | `IN_PROGRESS` / P0 |

详细矩阵见《[T1 证据总清单](./01_T1证据总清单.md)》，风险和成本见《[T1 风险差距、成本与 T2 建议](./02_T1风险差距成本与T2建议.md)》。

## 6. RTM 状态与退出差距

| 状态 | 数量 | 退出含义 |
|---|---:|---|
| `ACCEPTED` | 2 | 仅 T1 治理和范围已由项目发起人接受 |
| `IN_PROGRESS` | 9 | 内部合同/风险探针有 `STATIC/FAKE` 证据，但未达到商业验收 |
| `READY` | 1 | `T1-UAT-001` 等待退出评审签署，不自动转为接受 |
| `BLOCKED` | 7 | 主认证机、打印、扫码、电子秤、钱箱/客显、支付沙箱、设计伙伴 |
| `DEFERRED` | 2 | 鲸熵汇资料和三类商业发布许可证事项 |

本轮没有把任何 T1 执行需求改成 `ACCEPTED`。即使项目发起人接受本报告，外部实证需求仍需保留其真实状态，并在相关正式模块准入、硬件认证、支付联调、试点和商业发布前补证。

## 7. GitHub Actions 最终候选结果

Week 4 候选运行时间为 2026-08-16 11:58:40—12:16:21（Asia/Shanghai），`run_attempt=1`，提交 `8354ac55bf66304c4a911ea07f2b2a949589a5bc`，总结果 `success`。

| Job | Job ID | 结果 | 核验范围 |
|---|---:|---:|---|
| [reproduce-ubuntu](https://github.com/eiven-xxw/jshPOS/actions/runs/31925516509/job/95112356342) | 95112356342 | PASS | 干净 Ubuntu 执行器复跑 Week 1—3，约 491 秒 |
| [reproduce-windows](https://github.com/eiven-xxw/jshPOS/actions/runs/31925516509/job/95112356340) | 95112356340 | PASS | 干净 Windows 执行器复跑 Week 1—3，约 1,002 秒 |
| [supply-chain-security](https://github.com/eiven-xxw/jshPOS/actions/runs/31925516509/job/95112356360) | 95112356360 | PASS | 依赖差异、SBOM、许可证、Secret 和 HIGH/CRITICAL 门禁 |
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31925516509/job/95112356389) | 95112356389 | PASS | T0 tag、RTM、合同、范围、证据等级和 Week 4 静态校验 |
| [compare-reproducibility](https://github.com/eiven-xxw/jshPOS/actions/runs/31925516509/job/95114100786) | 95114100786 | PASS | 双平台输入树、seed、断言、事实摘要和归一化证据比较 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31925516509/job/95114123376) | 95114123376 | PASS | 生成 25 项文件索引和 T1 Week 4 退出证据包 |

同一提交的 Week 1 [#31925516528](https://github.com/eiven-xxw/jshPOS/actions/runs/31925516528)、Week 2 [#31925516534](https://github.com/eiven-xxw/jshPOS/actions/runs/31925516534) 和 Week 3 [#31925516533](https://github.com/eiven-xxw/jshPOS/actions/runs/31925516533) 均为 `success`。

## 8. 制品、SBOM、许可证与安全摘要

| 制品 | Artifact ID | 大小 | GitHub 归档 SHA-256 | 到期时间（UTC） |
|---|---:|---:|---|---|
| `t1-week4-exit-review-bundle` | 9257902599 | 40,830 B | `d4c86964d88c632ca4bd9091218cd2eaed0d8f2047b85f81ace63f5d68655a9e` | 2026-09-15 04:16:16 |
| `t1-week4-reproducibility-comparison` | 9257898563 | 2,120 B | `6e6cc616…` | 2026-09-15 |
| `t1-week4-windows-evidence` | 9257895254 | 14,768 B | `85747414…` | 2026-09-15 |
| `t1-week4-ubuntu-evidence` | 9257795621 | 14,743 B | `738ce1c1…` | 2026-09-15 |
| `t1-week4-supply-chain-evidence` | 9257705205 | 2,177 B | `7d1e7bd…` | 2026-09-15 |
| `t1-week4-governance-evidence` | 9257703413 | 4,255 B | `54303bcb…` | 2026-09-15 |

退出证据包已独立下载并复核：GitHub 归档摘要一致；包内共 26 个文件，证据索引覆盖其中 25 个内容文件，逐项大小和 SHA-256 复算差异为 0。关键摘要：

- 证据索引：`921ba7497ea71af704beb847c79a791ddeee58a3b37549ee105a1461ea6abe8d`；
- T1 PoC CycloneDX SBOM：`0b39eb523f2ae00c591622d00d4684dceb839c8343135d2e48b7d00cadbc2b7e`；
- 许可证摘要：`0c220ea54b817bb2ce1fd6a97dcc08edda30f45af3612d9b2fd1cc8ea7681dc0`；
- 安全摘要：`8634b66e791a7828cad3fa5e032fcf6f3bda4e4c72b1aa007c4606b1f3a3eaa4`；
- 比较结果：`3865c1f2067b591488081827c38e07ce577ba7270b7c8a9996be3ac88776ff9a`。

T1 PoC 只使用 Python 标准库，因此 T1 范围 SBOM 的组件列表为空；这表示 T1 未增加第三方运行依赖，不替代 T0 的全产品 SBOM。CI 使用的 5 个 Action 均固定到完整提交 SHA，不作为交付运行时组件。Aviator、simple-http、MySQL Connector/J 的替换或法务处置仍阻断商业发布，不能因为 T1 无新增依赖而关闭。

## 9. 风险、成本与架构建议

### 9.1 风险结论

- P0：主认证机/关键外设、支付沙箱、Android 物理断电、正式租户技术栈、APK/Schema 实机升级仍无高等级证据；
- P1：钱箱/客显、设计伙伴、商业许可证和主机数据包性能仍需在对应里程碑前解决；
- P2：双平台全量回归耗时、PoC 与正式实现漂移、GitHub 原生 Advanced Security 缺失需要持续补偿控制。

### 9.2 规划成本

- T2-Prep 预计 8—15 人日、3—5 个工作日；
- 主认证机和五类外设 PoC 预计 20—40 人日，外部关键路径 2—6 周；
- 首家支付沙箱端到端预计 15—30 人日，外部关键路径 2—5 周；
- 设计伙伴和合规脱敏样本预计 10—25 人日，外部关键路径 2—6 周；
- 商业 V1 正式研发、测试和实施为规划级 40—70 人月、6—9 个月，须在 T2-Prep 重新校准。

AI 可降低编码、测试、文档和排错投入，但不能替代支付签约、硬件认证、计量/法务/等保、真实断电和门店试点。

### 9.3 架构建议

1. 若本报告获确认，只进入独立的 `T2-Prep`，先冻结正式 RTM、模块依赖、迁移/API/事件契约和门禁，不直接铺开业务代码；
2. 正式实现采用模块化单体，先完成租户、组织、门店、权限审计与商品主数据基础，再逐模块准入订单、支付、库存、促销；
3. POS SQLite、同步、支付状态机必须引用详细设计 31—35 的不变量，禁止把 PoC 的 `syn_*` 表复制为生产表；
4. 设备厂商、支付 Provider 和鲸熵汇差异只进入适配器/连接器层，不污染核心状态机；
5. 硬件、支付沙箱、设计伙伴和许可证建立并行解阻轨道，分别阻断相应模块验收或商业发布。

## 10. Go / Conditional Go / No-Go 建议

| 选项 | 判定 | 原因 |
|---|---|---|
| `GO` 结束 T1 并直接进入 T2 业务编码 | **不建议/条件不满足** | 7 项外部实证阻断、P0/P1 未清零 |
| `CONDITIONAL GO` 接受 T1 内部基线并进入 `T2-Prep` | **建议，等待项目发起人确认** | Week 4 双平台、安全、供应链、证据门禁全绿，且阻断边界真实保留 |
| `NO-GO` | 当前未触发 | 若出现双平台不一致、未修复失败 seed、证据污染或项目不接受并行风险，则自动转入 |

因此本报告的退出建议是：`T1 INTERNAL STATIC/FAKE CONDITIONAL PASS`，只建议在项目发起人明确确认后进入 `T2-Prep`。在确认之前，ADR-018 保持 `Proposed`，`T1-UAT-001` 保持 `READY`，T1 不结束，T2 仍为 `NO-GO`。

## 11. 评审签署与下一步

| 角色 | 当前状态 | 可签署结论 |
|---|---|---|
| 架构/开发/QA/安全 | 技术材料已准备 | 内部 `STATIC/FAKE` 基线可重复，外部风险未消除 |
| 设备/支付/产品伙伴负责人 | 外部输入未齐 | 保持相应 `BLOCKED`，不得签署实机、沙箱或试点通过 |
| 项目发起人 | **待确认** | 决定是否接受风险边界并只准入 `T2-Prep` |

未经项目发起人确认，不更新 ADR-018 为 `Accepted`，不把 `T1-UAT-001` 或其他 T1 执行需求改为 `ACCEPTED`，不创建 T2 基线 tag，不启动正式订单、支付、库存、促销开发。

建议项目发起人下一步发送：

```text
我确认《T1 Week 4 周门禁暨 T1 退出评审准备报告》，接受 T1 内部 STATIC/FAKE 风险基线 CONDITIONAL PASS，并同意按 CONDITIONAL GO 进入 T2-Prep。

请继续保持实机、外设、支付沙箱、设计伙伴和商业许可证事项的 BLOCKED/DEFERRED 状态，不得将相关 T1 需求改为 ACCEPTED，不得用 Fake 替代外部证据。

本阶段只允许正式开发启动准备，暂不开发订单、支付、库存、促销等业务代码：
1. 以 Week 4 最终封存提交建立 T2-Prep 候选技术基线，并准备 annotated tag；创建 tag 前向我确认；
2. 编制《T2项目章程》《T2范围与非目标》《T2 RTM》并为正式需求分配唯一 Requirement ID；
3. 确定便利店首发、零食折扣店和社区超市差异边界，冻结商业 V1 模块清单；
4. 按租户/组织/门店、商品主数据、订单、支付、库存、促销的顺序建立模块准入和依赖图，不得一次铺开；
5. 复核详细设计 31—40 的状态机、不变量、权限、审计、幂等、迁移、回滚、API 与事件契约；
6. 制定 T2 迭代、人员 RACI、环境、数据库迁移、测试矩阵、CI 门禁、灰度与回退计划；
7. 为硬件、支付沙箱和设计伙伴建立并行解阻计划及资料截止点；
8. 完成后提交《T2正式开发启动评审报告》，等待我确认。

未经我确认不得创建 T2 基线 tag，不得进入 T2 正式业务编码，不得宣称可商用。
```

