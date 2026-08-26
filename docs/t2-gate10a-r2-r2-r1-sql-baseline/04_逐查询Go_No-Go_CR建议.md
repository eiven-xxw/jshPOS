# 逐查询 Go / No-Go / CR 建议

## 决策规则

- 租户或只读权限失败：`NO_GO_SECURITY`。
- 来源摘要、层级、参数、逻辑/实际计划缺失：`NO_GO_EVIDENCE`。
- 需要新增/调整索引：`CR_REQUIRED_FORWARD_MIGRATION`。
- 需要新增分页或改变响应形态：`CR_REQUIRED_COMPATIBILITY`。
- 计划或线性查询放大需要运行时改动：`NO_GO_RUNTIME_REMEDIATION_REVIEW`。
- 无需运行时变化且边界通过：`GO_CANDIDATE_KEEP_CURRENT_IMPLEMENTATION`。

逐查询结果必须由 GitHub MySQL 8.4 Artifact 回填。本文件在可执行证据产生前不预判绿色，尤其不能以
准备阶段静态审计替代执行计划。
