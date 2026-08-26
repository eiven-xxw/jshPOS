# CR-T2G10A-006：Gate 10A-R2 准备 CI 证据回填

- 日期：2026-08-26
- 类型：治理与证据回填
- 状态：Accepted
- 候选提交：`6995e4fe6e4cdd52a73b4c27fc1e4c5249ca1223`
- GitHub Run：`32948724263`

## 决策

记录候选提交在 Ubuntu、Windows 与 Temurin Java 21 干净执行器上的完整成功结果，以及四个
Artifact 的 GitHub SHA-256。该回填不修改运行时、SQL、依赖、配置、数据库或迁移，不改变
R2 三项 Finding 的 `PREPARED_AWAITING_SPONSOR_CONFIRMATION` 状态。

## 边界

该证据只支持提交《Gate 10A-R2 启动评审报告》。未经项目发起人再次确认，不接受 ADR-074，
不进入任何正式整改，不关闭 Finding，不提升外部证据等级。
