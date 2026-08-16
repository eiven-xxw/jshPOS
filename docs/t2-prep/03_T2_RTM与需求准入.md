# T2 RTM 与需求准入规范

## 1. 单一真相

机器权威为 `docs/governance/rtm.csv`。本文件只解释规则，不复制完整 RTM，防止两份清单漂移。

T2 使用两类前缀：

- `T2P-*`：T2-Prep 治理和启动材料；
- `T2-*`：T2 Alpha 正式业务/质量需求。

原 `V1-*` 行保留为商业路线图 Epic，不可直接进入编码；必须由一个或多个 `T2-*` 原子需求承接。

## 2. ID 规则

格式为 `T2-<DOMAIN>-NNN`，例如 `T2-ORD-001`。Domain 固定使用：

| 代码 | 领域 | 代码 | 领域 |
|---|---|---|---|
| IAM/ORG/RBAC | 租户组织权限 | CFG/AUD/TRM | 配置审计终端 |
| PRD/PRC/DPK | 商品价格数据包 | POS/ORD/OFF/SYN | POS订单离线同步 |
| PAY/REF/REC | 支付退款对账 | INV/PUR/CST/TRF | 库存采购成本调拨 |
| PRM/MEM/RPT | 促销会员报表 | SEC/OBS/BAK/UPG/MIG | 安全运维发布迁移 |
| HWD/PAR/JSH/LIC | 外部解阻 | UAT/REL | Alpha 验收发布决策 |

ID 一经分配不得复用或改义。废弃需求保留行并转 `DEFERRED`，用 notes 指向替代 ID。

## 3. 状态语义

| 状态 | T2 含义 |
|---|---|
| `DRAFT` | 需求已登记但未通过编码准入 |
| `READY` | 已通过模块评审，可在明确授权波次编码 |
| `IN_PROGRESS` | 已开始正式实现，必须有分支/PR 证据 |
| `IMPLEMENTED` | 代码完成但证据尚未全部验证 |
| `VERIFIED` | 所需自动化/集成/实证已通过，等待业务验收 |
| `ACCEPTED` | 对应阶段验收角色已签署 |
| `BLOCKED` | 缺少不可替代的前置输入/外部证据 |
| `DEFERRED` | 明确不在当前阶段处理 |

T2-Prep 中正式 `T2-*` 只允许 `DRAFT/BLOCKED/DEFERRED`。项目发起人确认第一波次后，只有该波次逐项通过准入的需求可转为 `READY`。

## 4. 追踪关系

每行必须包含：来源、原子验收、状态、实现位置、测试证据、Owner 和限制。跨域需求还需在模块依赖文档中声明：

- 上游 Requirement ID；
- 数据主权 Owner；
- 同步/异步契约；
- 幂等键与版本；
- 失败时责任域和补偿；
- 允许的证据等级。

## 5. T1 证据继承

T1 证据只能进入 T2 notes 的“风险输入”，不能填入正式实现或商业验收证据：

| T1 输入 | T2 用途 | 不得推导 |
|---|---|---|
| SQLite/Outbox Fake | 约束正式离线设计和故障用例 | 正式 SQLite 已实现/已断电通过 |
| 租户攻击 Fake | 生成正式 Mapper/SQL/任务/缓存攻击矩阵 | RuoYi 正式隔离已通过 |
| 五家支付 Fake | 约束 Provider 契约和 UNKNOWN 状态 | 任一支付机构已接入 |
| 设备 Fake | 约束能力/错误/超时接口 | 厂商机型已认证 |
| 数据包/升级 Fake | 约束版本、摘要、原子切换/前向修复 | Android 性能或 APK 升级已通过 |

## 6. 变更与审计

- RTM 变更必须与同一 CR/ADR/评审材料提交；
- 状态提升必须引用可复核证据，不能只写“已完成”；
- `BLOCKED/DEFERRED` 解除必须包含提供方、版本、日期、环境和验收人；
- CI 校验唯一 ID、必填字段、T1 状态不变、T2-Prep 状态边界和外部阻断；
- 每个 Sprint 结尾生成 RTM 差距报告，未关联测试的 P0 自动 `NO-GO`。
