# CR-T2G10A-010：Gate 10A-R2-R2-R1 SQL 可执行红基线

- 日期：2026-08-26
- Finding：`G10A-SQL-P2-001`
- 基线：`8eb77ec855b7bf89f93eedf4c01f7681465f0544`
- 分支：`t2/gate10a-r2-r2-r1-sql-executable-baseline`
- 状态：`APPROVED / EXECUTABLE_BASELINE_IN_PROGRESS`

## 授权

项目发起人接受 R2-R2 准备阶段 `CONDITIONAL PASS`，仅授权增加测试作用域的 MySQL 8.4.11
合成夹具、12条正式 Mapper 查询适配、执行计划、JDBC查询数、租户/只读权限攻击和证据流水线。

## 影响与停止线

本批不修改生产 Java、Mapper Java/XML、SQL、索引、数据库对象、依赖、配置或任何已发布迁移。
若证据表明需要索引/数据库对象变化，必须停止并申请独立 CR 与唯一前向迁移；若需要改变 API、
分页响应、资金、库存、租户、支付、同步、Owner 或幂等语义，必须申请 Requirement ID 和独立 CR。

## 证据边界

10k/100k 为固定 GitHub 执行器合成基线，1m 只运行获批趋势项。结果只形成后续整改的
`GO/NO_GO/CR_REQUIRED` 建议，不构成生产容量、商业 SLA、完整 Alpha 或商业验收。
