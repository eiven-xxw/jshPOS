# T2-Prep 候选基线与 Annotated Tag 计划

## 1. 当前事实

| 项目 | 值 |
|---|---|
| 基线来源提交 | `962c4ed5e631bccd5c6fff737ed8e97fb665fd03` |
| 基线来源分支 | `t1/prep-20260816` |
| 当前候选分支 | `t2/prep-20260816` |
| 候选 tag 名称 | `t2-prep-baseline-2026-08-16` |
| tag 类型 | annotated |
| tag 状态 | `PREPARED_NOT_CREATED` |

## 2. Tag 目标规则

Tag 最终应指向完成 T2-Prep 文档、RTM、ADR、CI 和启动评审报告且所有门禁通过的最终封存提交，而不是仅指向来源提交。最终 SHA 只能在候选分支封存后写入评审报告和项目发起人确认指令，避免文档自引用导致目标不断变化。

Tag 创建前必须验证：

1. 最终提交是 `962c4ed5…` 的后代；
2. 本地与 GitHub T2-Prep 门禁全部成功；
3. 远端分支 SHA 与本地一致，工作区洁净；
4. 正式业务目录相对基线无变化；
5. T1/T2 外部阻断和许可证状态不变；
6. 项目发起人明确确认 tag 名称、目标 SHA 和注释；
7. 远端不存在同名 tag。

## 3. 计划注释内容

```text
鲸熵汇收银系统 T2-Prep 基线

- Based on T1 Week 4 final: 962c4ed5e631bccd5c6fff737ed8e97fb665fd03
- Scope: charter, frozen V1 scope, T2 RTM, module gates, design 31-40 review, iteration/RACI, migration/test/CI, rollback and external unblock plans
- Decision: T2 CODING remains NO-GO until sponsor confirmation
- Evidence: final T2-Prep GitHub quality gates and review report
- Limitations: payment sandbox, real devices/peripherals, design partners remain BLOCKED; JSH/licensing remain DEFERRED
```

## 4. 计划命令（当前禁止执行）

```powershell
git tag -a t2-prep-baseline-2026-08-16 <SP_CONFIRMED_FINAL_SHA> -m "鲸熵汇收银系统 T2-Prep 基线"
git push origin refs/tags/t2-prep-baseline-2026-08-16
```

这些命令仅用于评审说明。未经项目发起人确认不得执行，也不得创建轻量 tag 或本地占位 tag。

## 5. 创建后的核验计划

- `git cat-file -t` 必须返回 `tag`；
- 本地和远端 peeled commit 必须等于确认的最终 SHA；
- GitHub API/页面可见 annotated tag；
- tag message、tagger、时间和证据链接归档；
- RTM/启动报告回填 tag；
- 发现目标错误时停止，不强制移动已发布 tag，使用事故/更正流程。
