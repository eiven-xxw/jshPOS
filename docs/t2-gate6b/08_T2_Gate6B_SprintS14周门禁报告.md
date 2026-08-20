# T2 Gate 6B / Sprint S14 周门禁报告

> 报告状态：`CONDITIONAL PASS — T2-UPG-001 VERIFIED，等待项目发起人确认`
>
> 分支：`t2/gate6b-sprint14-20260820`
>
> Gate 6A 起点：`ec26f18715c205f29f7b9ec7c8a6478aeffcc557`
>
> 全绿候选：`da1df4f329e468b3caee5bf0ed638ceaeeeae543`
>
> GitHub Actions：[Run 32333907801](https://github.com/eiven-xxw/jshPOS/actions/runs/32333907801)

## 1. 阶段结论

`T2-UPG-001` 已完成设计准入、Provider 无关正式实现、干净执行器全量回归和证据封存，状态由 `IN_PROGRESS` 更新为 `VERIFIED`，建议 Gate 6B `CONDITIONAL PASS`。本次不将需求更新为 `ACCEPTED`，须等待项目发起人确认。

证据上限仍是 `STATIC/UNIT/MYSQL8.4_SYNTHETIC/SYNTHETIC_PACKAGE/SOFTWARE_EXECUTION`。实现没有发送支付 Provider 网络请求，没有安装真实 APK，没有调用厂商静默升级 SDK、固件、重启或远程终端命令，也没有使用生产密钥、真实资金、真实 PII 或未经授权云资源。

## 2. 已实现能力

- 新增独立 `jshpos-release` Release Owner，承载七类发布物、发布、灰度、终端软件任务、只追加事件与审计；Gate 6A 设计夹具未转成生产表。
- 发布物绑定 release ID、类型、版本、渠道、可信目标范围、SHA-256、Ed25519 签名、密钥版本、构建 commit、SBOM 及协议/Schema/系统兼容窗口。
- 服务端通过 Terminal Registry 可信端口核验租户、门店、终端、版本和能力；客户端自报身份或范围不能形成授权依据。
- 三套具名状态机使用稳定幂等键和内容摘要；同键异内容、跨租户/门店、吊销终端、旧包重放、下载中断、坏签名/摘要、待同步 Outbox、UNKNOWN 支付/退款、营业保护、迁移或健康失败均失败关闭。
- MySQL V40/V41 只前进；发布物、发布/灰度/任务、事件、审计均按数据访问策略登记，Schema 失败分流到安全前向修复。
- 冻结 OpenAPI、事件/JSON Schema、17 个固定故障向量、攻击矩阵、运行手册、兼容/回退/容量和证据契约。

## 3. CI 与量化结果

GitHub Run `32333907801` 的十个 Job 单次全部成功，没有跳过失败测试、自动重跑、绿色占位或阈值下调：

| Job | 结果 | 核验重点 |
|---|---|---|
| governance | PASS | AGENTS/ADR/RTM/CR、目录、契约、迁移摘要和阶段边界 |
| server | PASS | 47 模块全量构建；Gate 6B 新增 15 项测试；核心行/分支覆盖率保持 ≥90%/≥85% |
| mysql-migration-capacity | PASS | MySQL 8.4，V1—V41 校验，权限/只追加保护与 100k 事件容量 |
| release-security-faults | PASS | 可信租户/终端、跨租户/门店、签名摘要、幂等、Owner 边界及失败关闭 |
| synthetic-vectors | PASS | 固定 seed 的 17 个向量全部通过，失败 seed 为空 |
| pos-linux | PASS | Flutter Linux、SQLite 及既有 Gate 全量回归 |
| pos-windows | PASS | Flutter Windows、SQLite 及既有 Gate 全量回归 |
| admin-web | PASS | Vue/Web 构建与既有回归 |
| security-sbom-license | PASS | Secret、依赖漏洞、双 SBOM、许可证与 Android/Kotlin 回归 |
| evidence | PASS | 汇总上游九类制品、摘要和证据边界 |

机器化 Gate 6B 检查结果：`registeredTables=7`、`migrationCount=2`、`vectorCount=17`、`networkCalls=0`、`realDeviceCommands=0`、`alphaClaimAllowed=false`。

## 4. 供应链修复

前一候选的安全门禁发现 `io.netty:netty-transport-sctp 4.1.136.Final` 对应 `CVE-2026-59902 HIGH`。本次统一升级到修复版本 `4.1.137.Final`，重建依赖树和 CycloneDX SBOM；本地 SBOM 中旧版本命中为 0，完整 CI 的 HIGH/CRITICAL 门禁恢复全绿。未使用 ignore、VEX 豁免、依赖排除或阈值降低。

## 5. 封存制品

| Artifact | ID | GitHub SHA-256 |
|---|---:|---|
| t2-gate6b-evidence-index | 9394169406 | `82ff9b9958881847f52e3b2c1c8c9b6cabf05bd62e5391f66cb8b263db982dbb` |
| t2-gate6b-governance | 9393947712 | `2f732f1dd940460ae8a51667e10c810835b5e6e9576e769b6842741fe66e445d` |
| t2-gate6b-mysql | 9394027972 | `dc036019da589c828b02c9f688762f3fc0a96d7843c2d5ecc69f535525272666` |
| t2-gate6b-pos-linux | 9394041568 | `7a7ebb0cb7de82b70f38267e4af8e49d750bc9dce530a338e0b4e8512b197de5` |
| t2-gate6b-pos-windows | 9393988554 | `7af60b5234219b9e6b8336283a91269561f0b898447b2472c0fffa71a1d114fd` |
| t2-gate6b-release-security | 9394020268 | `813147e5061527bab45598495aad6356a905a28a174611e945ec08a4c3a78a22` |
| t2-gate6b-security | 9394158890 | `0ea5e9f266a976e57ab2a85c476c3643d850ccab79e6f543e36143b871380f26` |
| t2-gate6b-server | 9394150636 | `b7c41b0b61751ffc740982111fff4d29aaca843fe5cfcba23ad3a386f2d877a6` |
| t2-gate6b-vectors | 9393947699 | `e7f4d7635f255910bf40069e10a501e3fb9ac49ebd0a6f48cc619cc34a506f5e` |
| t2-gate6b-web | 9393968850 | `4f9ecc9ef9313cbb3a676fd8172149527e33e611e42eabba248970db7d744c09` |

## 6. 未解除边界

- `T2-PAY-002`、`T2-HWD-001`、`T2-PAR-001` 继续 `BLOCKED`；分别缺独立支付沙箱、实机和设计伙伴证据。
- `T2-UAT-001`、`T2-REL-001` 继续 `DRAFT`；支付、硬件、伙伴任一 P0 未解除时不得启动完整 Alpha UAT。
- T2-TRM-001 仍不等于 REAL_DEVICE；T2-BAK-001 仍仅代表 SYNTHETIC_RESTORE，RPO/RTO 不构成生产灾备或商业 SLA。
- 生产 KMS、真实对象存储/CDN、跨区域灾备、真实 PITR、生产发布和切换继续 `BLOCKED/ASSUMPTION`。

## 7. 评审建议

建议项目发起人接受 Gate 6B `CONDITIONAL PASS`，并在明确保留上述证据边界后，授权将 `T2-UPG-001` 从 `VERIFIED` 更新为 `ACCEPTED`。未经确认，不启动 Gate 6C、不启动完整 Alpha/UAT，也不宣称系统可试点或可商用。
