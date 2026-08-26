# Action Node 24 与供应链整改说明

## 范围与策略

R1 对仓库全部 GitHub Actions 工作流执行同等供应链维护：每个远程 Action 必须使用
40 位不可变 commit SHA，并在行尾保留对应发布版本。活动工作流为 `ci.yml`、
`dependency-review.yml` 和 `t2-gate10a-r1.yml`；其他工作流保留历史用途和 Run 证据，
只接受等价的 Action pin 维护，不改变原门禁语义。

## 冻结结果

| Action | 原版本/运行时 | R1 版本/运行时 | 处理 |
|---|---|---|---|
| actions/checkout | v4.2.2 / Node 20 | v7.0.1 / Node 24 | 固定 SHA |
| actions/setup-java | v4.7.1 / Node 20 | v6.0.0 / Node 24 | 固定 SHA |
| actions/setup-node | v4.4.0 / Node 20 | v7.0.0 / Node 24 | 固定 SHA |
| actions/setup-python | v5.6.0 / Node 20 | v7.0.0 / Node 24 | 固定 SHA |
| actions/upload-artifact | v4.6.2 / Node 20 | v7.0.1 / Node 24 | 固定 SHA |
| actions/download-artifact | v8.0.1 / Node 24 | v8.0.1 / Node 24 | 保持 |
| pnpm/action-setup | v4.2.0 / Node 20 | v6.0.10 / Node 24 | 固定 SHA |
| subosito/flutter-action | v2.23.0 / composite | v2.23.0 / composite | 保持 |

精确 SHA、上游 `action.yml/action.yaml` 摘要、许可证和逐项回退值由
`contracts/t2/gate10a-r1/action-version-ledger-v1.json` 统一管理。治理脚本拒绝
浮动 tag、未知 Action、账外 SHA、版本注释漂移和非 Node 24 JavaScript Action。

## 回退

回退只允许使用版本账中的原 SHA，从新的 Git 提交重新运行完整 R1 CI；禁止移动 Gate 9C
tag、修改失败 Run、仅重跑失败 Job或降低安全阈值。若单个 Action 新版存在兼容问题，
必须保留失败证据并提交最小修复，不得把应用业务改动混入回退。
