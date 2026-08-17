# T2 Gate 6A / Sprint S13 周门禁报告

> 文档编号：JSH-POS-T2-G6A-008
> 日期：2026-08-18
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> Gate 6A 分支起点：`1bd27f70d39dd2056ffecf3b25f07aa9c7953606`
> TRM 独立封存提交：`cc939ee2096564ea48f489cffeffbffaa840064f`
> BAK 全绿实现候选：`ece4c375144a7ab4abf7ac35ab62b243bf612ed8`
> Gate 6A 状态与报告封板提交：`68a0f758045d086e990ef1e994ba94c6c8f0e4b2`
> TRM 独立 CI：[Run 32044586171](https://github.com/eiven-xxw/jshPOS/actions/runs/32044586171)
> BAK 完整 CI：[Run 32048133777](https://github.com/eiven-xxw/jshPOS/actions/runs/32048133777)
> 状态与报告封板 CI：[Run 32049647953](https://github.com/eiven-xxw/jshPOS/actions/runs/32049647953)
> 当前结论：`CONDITIONAL PASS / VERIFIED / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 6A 获准的两项需求已严格按 `T2-TRM-001 → T2-BAK-001` 完成顺序准入、实现和独立门禁。TRM 在独立封存提交及完整 CI 通过并更新为 `VERIFIED` 后，才将 BAK 从 `DRAFT` 准入为 `IN_PROGRESS`，没有并行铺开或倒补准入材料。

`T2-TRM-001` 建立了服务端分配的终端身份、一次性激活凭据、只存摘要的设备凭据、租户/组织/门店绑定、能力和版本快照、轮换/吊销、可信上下文与只追加审计。`T2-BAK-001` 建立了六类来源的规范备份清单、AES-256-GCM 加密、密钥与数据分离、不可变对象目录、空隔离目标恢复、九项恢复校验、百万合成事实重建及 RPO/RTO 自动计时。

BAK 候选在 GitHub Ubuntu、Windows 与 MySQL 8.4 干净执行器完成 10 个 Job，全部 `success`，总耗时 11 分 35 秒，生成 10 个可见 Artifact。建议 Gate 6A 结论为 `CONDITIONAL PASS`，`T2-TRM-001`、`T2-BAK-001` 保持 `VERIFIED`；只有项目发起人明确确认后才能更新为 `ACCEPTED`。

状态、RTM、报告和下一步指令封板提交 `68a0f758…` 又在 Run `32049647953` 完成 10/10 Job、10 个 Artifact 的全量复跑，耗时 7 分 20 秒；因此候选实现证据与封板治理状态均已受独立 CI 约束。

最高证据等级为 `STATIC + UNIT + MYSQL8.4_SYNTHETIC + SYNTHETIC_RESTORE`。生产 KMS、真实对象存储、跨区域灾备、真实 PITR、主认证终端、支付沙箱和试点证据均为 0；因此本结论不代表完整 Alpha、实机验收、试点就绪或可商用。

## 2. 需求状态与边界

| Requirement ID | 状态 | 已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-TRM-001` | `VERIFIED` | 登记、一次性激活、摘要凭据、轮换/吊销、版本/能力、门店绑定、可信上下文、100k 终端容量、不可变审计 | Android Keystore/硬件证明、厂商 SDK、主认证机和外设实机 |
| `T2-BAK-001` | `VERIFIED` | 六类合成来源、规范清单、AES-256-GCM、摘要/保留、空环境恢复、九项守恒、百万事实、RPO=300 秒、RTO=1 秒 | 生产 KMS、真实云对象存储、跨区域复制、真实数据库 PITR、生产灾备切换和商业 SLA |
| `T2-UPG-001` | `DRAFT` | 仅版本契约、签名/摘要、兼容窗口、灰度状态机、营业保护、健康检查、回退/前向修复设计和合成向量 | 升级运行时、真实 APK 安装、厂商静默升级、固件兼容和实机回退 |
| `T2-UAT-001` / `T2-REL-001` | `DRAFT` | 差距、证据目录、角色签署和 Go/No-Go 模板 | 未启动完整 Alpha 验收或发布评审 |
| `T2-PAY-002` / `T2-HWD-001` / `T2-PAR-001` | `BLOCKED` | 仅内部合成与资料清单 | 支付沙箱、真实设备和设计伙伴均未解阻 |

既有 Gate 0—5D 的 `ACCEPTED` 状态保持不变；`T2-JSH-001`、`T2-LIC-001` 保持 `DEFERRED`。本轮没有用软件生成密钥、本地文件对象存储或合成恢复解除任何外部阻断。

## 3. 顺序准入和提交证据

| 顺序 | 需求 | 关键提交/CI | 结果 |
|---:|---|---|---|
| 1 | `T2-TRM-001` | 候选 `82591230563de40f4c29420523313b3342127dbb`；独立封存 `cc939ee2096564ea48f489cffeffbffaa840064f`；Run `32044586171` | `VERIFIED` 后才准入 BAK |
| 2 | `T2-BAK-001` | 准入 `474e9d65b4ef7df38bc04847279b771a8a615d85`；实现候选 `ece4c375144a7ab4abf7ac35ab62b243bf612ed8`；Run `32048133777` | `VERIFIED`，等待发起人确认 |

终端运行时继续位于 Sync Owner 边界；备份恢复运行时位于独立 Resilience Owner。复杂条件更新、只追加对象、审计和恢复检查使用显式 MyBatis XML，SQL 强制携带可信租户范围；Controller 不承载领域规则。核心实体、规则、服务、端口、迁移和关键不变量保留中文注释。

## 4. 核心不变量与安全控制

- 终端 ID、租户、组织/门店、认证状态、版本和能力由服务端权威分配；客户端声明不得提升权限。
- 激活秘密仅返回一次，持久化只保存 HMAC 摘要；轮换、吊销、克隆、过期、时钟偏移和重放均失败关闭并审计。
- POS 同步与数据包读取已登记终端可信上下文；任务、缓存、导出、对象路径和诊断包继续执行租户命名空间。
- 备份范围由受权服务端上下文提供，HTTP 请求不得携带密钥材料或自行扩大 tenant scope。
- 每个备份对象由作用域摘要、backup ID 和密文 SHA-256 定址，清单规范化后计算内容摘要；同幂等键异内容拒绝。
- AES-256-GCM 的 AAD 绑定备份、范围、路径、明文摘要和密钥版本；密钥不进入清单、API、日志或备份对象。
- 恢复必须从空目标开始，先完成清单/摘要/加密/版本校验，再恢复六类来源并执行 Flyway、投影、租户、业务日、游标和审计核对。
- 损坏、缺片、错密钥、跨租户替换、过期、迁移不兼容和恢复中断均 `FAIL_CLOSED`，不得用报表投影或 POS 本地库覆盖云端权威事实。
- Provider SDK/HTTP、真实终端命令、生产/沙箱密钥、真实资金、真实 PII 和未经授权云写入均为 0。

## 5. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | 46 个 Maven project；468 tests，0 failure/error/skipped |
| Resilience 覆盖率 | PASS | line 142/157 = 90.45%；branch 115/130 = 88.46%；高于 90%/85% 阈值 |
| MySQL 8.4 | PASS | 空库执行 39 个 Flyway 版本到 `202608180039`；二次 migrate=0；Flyway validate、权限与不可变触发器通过 |
| 终端容量 | PASS | 两个虚构租户、多组织/门店和 100,000 终端合成数据 |
| 恢复容量 | PASS | 1,000,000 条确定性合成事实摘要、投影重建和九项恢复检查 |
| RPO/RTO 内部目标 | PASS（仅合成） | 自动计时 RPO=300 秒、RTO=1 秒；`commercialSla=false`、`cloudDrEvidence=0` |
| 攻击面 | PASS | 67 个终端/租户/密钥/对象存储/恢复攻击面；外部网络、真实设备和云写入为 0 |
| 固定故障矩阵 | PASS | 36 个重复、同键异内容、克隆、吊销、损坏、缺片、错密钥、PITR 边界和前向修复场景 |
| Flutter/Android | PASS | Linux/Windows analyze 与回归、SQLite、Kotlin 编译和 Debug APK 构建；不等于 REAL_DEVICE |
| 安全与供应链 | PASS | Secret、IaC、依赖 HIGH/CRITICAL、服务端/Flutter SBOM 和许可证门禁通过 |

## 6. GitHub Actions Job 与制品

完整 BAK 运行 `#32048133777` 为单次完整执行，10 个 Job 全部通过：

| Job | Job ID | 结果/耗时 |
|---|---:|---|
| governance | 95440708864 | PASS / 9s |
| server | 95440709031 | PASS / 10m35s |
| mysql-migration-capacity | 95440708877 | PASS / 4m50s |
| terminal-resilience-security | 95440708924 | PASS / 4m15s |
| synthetic-vectors | 95440708815 | PASS / 12s |
| pos-linux | 95440708837 | PASS / 5m2s |
| pos-windows | 95440708931 | PASS / 2m23s |
| admin-web | 95440708909 | PASS / 1m3s |
| security-sbom-license | 95443604083 | PASS / 18s |
| evidence | 95443689199 | PASS / 33s |

| Artifact | ID | GitHub 显示大小 | GitHub digest |
|---|---:|---:|---|
| `t2-gate6a-evidence-index` | 9293964581 | 12.2 KB | `sha256:28f7c27c1f30dfd9386808c7f6028d0ceff424b6f11fbb6534d1c32600ccfa60` |
| `t2-gate6a-governance` | 9293624447 | 1.03 KB | `sha256:c6e1473bd1a08a3a1cd59805d3f74705cbda82633e5854d9709e9aa0af2de1d0` |
| `t2-gate6a-mysql` | 9293762911 | 12.2 KB | `sha256:75e27f2f72728d15821df675b4630f08435eb57be90d6b21d22831c7067b05e8` |
| `t2-gate6a-pos-linux` | 9293768899 | 71.4 MB | `sha256:0bb7634c2e7dffc90f486779286bedf502eb91132e281b19754ccc1b93bbdf00` |
| `t2-gate6a-pos-windows` | 9293688681 | 5.08 KB | `sha256:5c13a86022a68818d5291124d1b3b67f0a01e81377309785007570e236b2eb90` |
| `t2-gate6a-resilience` | 9293743501 | 1.8 KB | `sha256:c9a7ffd93e77deb6b36d040080b385c3ccaefda8d32edb40ad6d29500edf1f8d` |
| `t2-gate6a-security` | 9293947820 | 87.2 KB | `sha256:17f6a6574d3f2d9a140286d0da781e391d2b91bcdb382b3a1ca77b5295e9cbf7` |
| `t2-gate6a-server` | 9293936900 | 148 MB | `sha256:b75bcba855f6e3e891e7c25f7eca480ef35975ebdd4e8ad8b88599229a05b6a9` |
| `t2-gate6a-vectors` | 9293624645 | 2.08 KB | `sha256:9912010254054b12e70831f661dd2f30ce384d49467e541d5c872f9b6ea70f5d` |
| `t2-gate6a-web` | 9293650259 | 78.2 KB | `sha256:36d8c256e80b5671eb4ab256d56d54b1678a002df7b13cbf6700373bcd91681f` |

14 条 GitHub Annotation 均为所固定第三方 Action 的 Node 20 弃用提示或 Node 内部弃用警告，不是测试、安全、SBOM、许可证或证据失败。其升级应作为独立供应链维护项处理，不得通过取消 SHA 固定或关闭门禁消除警告。

## 7. 风险、阻断与不可宣称

- P0：`T2-PAY-002` 继续 `BLOCKED`；没有授权支付沙箱、正式接口、测试终端、签名/回调/退款/账单和技术联系人。
- P0：`T2-HWD-001` 继续 `BLOCKED`；没有主认证 Android、两种打印、扫码、电子秤、钱箱、客显、物理断电、长稳和真实升级回退证据。
- P0：`T2-PAR-001` 继续 `BLOCKED`；没有五家设计伙伴名单、三家书面试点意愿和授权脱敏样本。
- P0：生产 KMS、真实对象存储、跨区域复制、真实 PITR 与生产灾备切换未验证；本地合成 RPO/RTO 不构成商业 SLA。
- P1：`T2-UPG-001` 仍为 `DRAFT`，仓库未创建升级运行时、真实安装命令或远程终端执行器。
- 外部证据持续为 `sandbox=0`、`realDevice=0`、`pilot=0`、`cloudDr=0`。

## 8. 退出建议

建议项目发起人接受 Gate 6A `CONDITIONAL PASS`，并在明确确认后将 `T2-TRM-001`、`T2-BAK-001` 从 `VERIFIED` 更新为 `ACCEPTED`。

下一内部阶段建议为 Gate 6B / Sprint S14，仅准入 `T2-UPG-001` 的 Provider 无关升级治理与合成运行时，继续禁止真实 APK 安装和真实终端命令；同时并行推动支付沙箱、主认证硬件和设计伙伴资料解阻。三个 P0 外部阻断任一未解除前，不启动完整 `T2-UAT-001` 或宣称 Alpha。

本报告、UPG 阻断报告、RTM 和状态封存提交必须再运行完整 Gate 6A CI；若封存复跑不是全绿，本报告自动失效。发起人确认前，不得将 TRM/BAK 更新为 `ACCEPTED`，不得启动 Gate 6B、支付 Provider 网络或完整 Alpha/UAT。
