# Gate 9B 四项 Finding 与失败历史封存

## 1. 关闭账

| Finding | 当前状态 | 最终候选/Run | 主要证据 |
|---|---|---|---|
| `G9A-API-P1-001` | `CLOSED_IN_GATE9B` | `5ebcde37…` / `32728598791` | 300/300 API、差异 0/0 |
| `G9A-ASM-P1-001` | `CLOSED_IN_GATE9B` | `839b23f5…` / `32741052481` | 商业默认装配、演示面清除、SBOM |
| `G9A-UI-P1-001` | `CLOSED_IN_GATE9B` | `d7b238d8…` / `32832408085` | 20 Vue + 6 Flutter 联合页面证据 |
| `G9A-E2E-P1-001` | `CLOSED_IN_GATE9B` | `bf7a48bc…` / `32917121269` | 正式栈三业态、22 Owner、故障与守恒 |

机器来源为 `contracts/t2/gate9c-prep/finding-closure-register-v1.json`。Gate 9C 只引用并
计算摘要，不覆盖 Gate 9A 原始 `OPEN` 账或 Gate 9B 各阶段形成时的历史状态。

## 2. 失败历史

- R3D 证据回填曾因治理允许清单缺口失败关闭，失败 Run 保留；
- R4 从 `CR-T2G9R4-003` 至 `CR-T2G9R4-022` 保存正式运行栈逐次暴露的迁移约束、
  摘要规范化、兼容窗口、关联标识、Owner 白名单和证据序列化等失败；
- `CR-T2G9R4-023` 只记录最后完整门禁通过，不删除或改写前述失败；
- Gate 9C-Prep 两次完整 Run `32922801554`、`32923488551` 均保留并引用。

## 3. 封存原则

失败证据是系统恢复能力和门禁有效性的组成部分。封板不把失败改写为成功，不重跑失败 Job，
不删除失败 seed；修复链只能以新 CR、新提交和新的完整 Run 追加表达。
