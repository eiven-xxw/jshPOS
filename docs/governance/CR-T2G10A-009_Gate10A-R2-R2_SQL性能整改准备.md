# CR-T2G10A-009：Gate 10A-R2-R2 SQL 性能整改准备

- 日期：2026-08-26
- 状态：`SQL_PREP_IN_PROGRESS`
- Finding：`G10A-SQL-P2-001`
- 依据：项目发起人确认指令、ADR-074
- 起点：`f2a9f454d5c306142b71dbae398853ae17daab9e`

## 决策

1. 确认 `G10A-MTN-P2-001` 为 `CLOSED_IN_GATE10A_R2_R1`；
2. R2-R2 先冻结 MySQL 8.4 数据分布、关键查询、执行计划、索引、分页、N+1、超时、可信租户和权限边界；
3. 只添加治理、测试设计、静态审计、证据脚本和准备 CI，不修改运行时 SQL、Mapper、索引、数据库对象或迁移；
4. `G10A-SQL-P2-001` 保持 `PREPARED_AWAITING_SPONSOR_RUNTIME_CONFIRMATION`；
5. `G10A-RES-P2-001` 保持 `PREPARED`，不得提前进入长稳整改。

## 停止线

- 新索引或数据库对象：先提交独立 CR，只允许前向迁移；
- API、分页业务行为、资金、库存、租户、支付、同步、事件或 Owner 语义变化：停止并申请 Requirement ID 与 CR；
- 查询计划无法绑定可信租户/门店、出现跨租户读取或需要降低权限门禁：`NO-GO`；
- 不得使用本阶段合成计划宣称生产容量或商业 SLA。
