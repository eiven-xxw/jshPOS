# T2-Prep Annotated Tag 封存报告

> 文档编号：JSH-POS-T2P-TAG-001
> 日期：2026-08-16
> 结论：`T2-PREP TAG SEALED / T2 CODING NO-GO / AWAITING GATE 0/1 CONFIRMATION`

## 1. 封存对象

| 项目 | 值 |
|---|---|
| Tag | `t2-prep-baseline-2026-08-16` |
| 类型 | annotated tag |
| Tag 对象 SHA | `e534c3b8765b1a8ca8585f84195ef160ae8d873f` |
| 目标类型 | commit |
| Peeled commit | `557ba270479935d6b44968cf70b47033f7d3d656` |
| 来源基线 | T1 Week 4 final `962c4ed5e631bccd5c6fff737ed8e97fb665fd03` |
| Tagger | `Administrator` |
| Tagger 时间 | 2026-08-16 13:31:27（Asia/Shanghai） |
| GitHub API 对象 | `repos/eiven-xxw/jshPOS/git/tags/e534c3b8765b1a8ca8585f84195ef160ae8d873f` |

## 2. Tag message

```text
鲸熵汇收银系统 T2-Prep 基线

Based on T1 Week 4 final 962c4ed5e631bccd5c6fff737ed8e97fb665fd03.

Scope: charter, frozen V1 scope, T2 RTM, module gates, design 31-40 review, iteration and RACI, migration, test, CI, rollback and external unblock plans.

Decision: T2 coding remains NO-GO until sponsor separately confirms Gate 0/1.

Limitations: payment sandbox, real devices and peripherals, and design partners remain BLOCKED; JSH and licensing remain DEFERRED.
```

## 3. 创建前核验

- 工作区洁净，当前分支 HEAD 与授权目标均为 `557ba270479935d6b44968cf70b47033f7d3d656`；
- 目标对象类型为 `commit`，且是 Week 4 final 的后代；
- 本地 `refs/tags/t2-prep-baseline-2026-08-16` 不存在；
- `git ls-remote origin` 确认远端同名 tag 不存在；
- 最终 T2-Prep CI [#31928812709](https://github.com/eiven-xxw/jshPOS/actions/runs/31928812709) 对目标提交首次运行成功；
- 退出包 Artifact `9258675694`，ZIP SHA-256 为 `d860a91e4215ce6d8e12d581b6874135f470834a8a3f795733c2bedeb43994ac`。

## 4. 创建与远端核验

| 核验 | 结果 | 证据 |
|---|---|---|
| 本地对象类型 | PASS | `git cat-file -t` 返回 `tag` |
| 本地 peeled commit | PASS | `557ba270479935d6b44968cf70b47033f7d3d656` |
| 精确 ref 推送 | PASS | GitHub 返回 `[new tag]` |
| 远端 tag 对象 | PASS | `e534c3b8765b1a8ca8585f84195ef160ae8d873f` |
| 远端 peeled commit | PASS | `557ba270479935d6b44968cf70b47033f7d3d656` |
| GitHub 对象类型 | PASS | ref object=`tag`；target object=`commit` |
| Message | PASS | GitHub API 与本地完整多段 message 一致 |
| Tagger/时间 | PASS | GitHub API 可见且与 tag 对象一致 |

没有创建轻量 tag，没有移动已有 tag，没有使用 `--force`。

## 5. 基线证据

最终 CI #31928812709 包含：

- governance：T0 结构、106 项 RTM、20 个契约和 T2-Prep 边界；
- windows-boundary：Windows 干净执行器验证生产目录变化为 0；
- security：41 个扫描目标中 Secret 0、HIGH/CRITICAL Workflow 配置问题 0；
- evidence：证据包内 6 个内容文件逐项大小和 SHA-256 复算差异为 0。

证据包索引 SHA-256：`33d86d5974a56b9bc708c1e71566fe64dd5f4a15241f7204b1d91454c1ed28aa`。

## 6. RTM 与范围核对

| 范围 | 封存后状态 | 数量 | 变化 |
|---|---|---:|---|
| T1 | `ACCEPTED` | 2 | 不变 |
| T1 | `IN_PROGRESS` | 9 | 不变 |
| T1 | `BLOCKED` | 7 | 不变 |
| T1 | `DEFERRED` | 2 | 不变 |
| T1 | `READY` | 1 | 不变 |
| T2-Prep | `ACCEPTED` | 8 | 依据项目发起人确认由 READY 转 ACCEPTED |
| T2 正式业务 | `DRAFT` | 47 | 不变 |
| T2 外部实证 | `BLOCKED` | 4 | 不变 |
| T2 延期事项 | `DEFERRED` | 2 | 不变 |

相对 tag 目标，`server`、`admin-web`、`pos-flutter`、`packages/pos_device_adapter`、`infra` 没有业务变化。Tag 封存不授权订单、支付、库存、促销或其他正式开发。

## 7. 未解决限制

- 主认证 Android 设备和打印/扫码/电子秤/钱箱/客显仍无 `REAL_DEVICE` 证据；
- 主支付仍无授权 `SANDBOX`；
- 设计伙伴仍未落实；
- 鲸熵汇资料和商业许可证事项继续 `DEFERRED`；
- ADR-019 继续 `Proposed`；
- 系统不能宣称 T2 Alpha、试点或商业可用。

## 8. 下一步边界

下一步只能由项目发起人单独授权 Gate 0 首波正式开发，并允许 Gate 1 进行契约/迁移准备。订单、支付、库存、促销继续禁止；Gate 1 正式实现须等待 Gate 0 周门禁再次确认。
