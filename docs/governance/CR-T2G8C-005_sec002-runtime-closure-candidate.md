# CR-T2G8C-005：T2-SEC-002 运行时关闭候选

- 决策：`CONDITIONAL_PASS_AWAITING_SPONSOR_ACCEPTANCE`
- Requirement：`T2-SEC-002`
- 证据上限：`INTERNAL_SECURITY_HARDENING_CANDIDATE`
- 状态：完成完整 CI 后为 `VERIFIED`；未经项目发起人确认不得更新为 `ACCEPTED`

## 变更结论

仅关闭 `G8C-SEC-P0-001`、`G8C-SEC-P1-002`、`G8C-SEC-P1-003`：生产 Secret/启动门禁、管理端点最小暴露与服务附件资源边界。未新增业务能力、依赖或数据库迁移。

## 保留边界

`T2-MTN-001/T2-PERF-002/T2-RDY-001` 保持 `DRAFT`；PAY/HWD/PRN/PAR、UAT/REL、LIC/JSH 状态保持不变。所有外部执行计数为零。
