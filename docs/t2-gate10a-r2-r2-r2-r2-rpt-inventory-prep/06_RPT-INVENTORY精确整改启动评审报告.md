# RPT-INVENTORY 精确整改启动评审报告

## 当前结论

结论暂为 `PREP_IN_PROGRESS_RUNTIME_NOT_ADMITTED`。待独立 CI 完整通过并回填提交、Run、Job、
Artifact 和 MySQL 指标后，最高可建议 `PREP_VERIFIED_AWAITING_SPONSOR_CONFIRMATION`。

`G10A-SQL-P2-001` 继续 `OPEN`，`G10A-RES-P2-001` 继续 `PREPARED`。本报告不申请关闭
Finding，也不授权修改运行时。

## 已完成准备

- 冻结 v1 API、权限、Owner 端口、Mapper statement、排序和 SQL 摘要；
- 建立 7 个可复现失败 seed 和静态红基线；
- 建立 MySQL 8.4.11 10k/100k 当前计划、查询数、租户攻击、数量/成本汇总采集；
- 冻结版本化 keyset、HMAC 游标、Reporting 批量读取端口和流式导出恢复设计；
- 对比顺序对齐和门店优先两种候选索引，均保持未授权；
- 冻结兼容、回退、Owner 语义、迁移和外部执行停止线；
- 生产 Java/SQL/Mapper/API/事件/索引/数据库对象/依赖/迁移变化为 0。

## 风险与建议

当前无界列表和逐门店导出是确定风险；直接替换 v1 会扩大兼容风险，因此建议保留 v1，独立增加
v2 keyset 和批量端口。索引选择不能在准备阶段拍板，应先获准运行时实现最小查询，再在固定分布
对比执行计划；如果需要新索引，必须停下并申请独立索引 CR。

## Go/No-Go

- 当前：运行时 `NO-GO`，等待 CI 证据和项目发起人确认；
- CI 全绿后建议：`CONDITIONAL GO`，只准入 RPT-INVENTORY 运行时第一批；
- 索引/迁移：仍 `NO-GO`，必须独立 CR；
- RPT-PAY-REC、RES、R3、外部执行、完整 Alpha、生产：`NO-GO`。

## 待回填证据

- 候选 commit：`PENDING`；
- GitHub Run：`PENDING`；
- MySQL 10k/100k 结果：`PENDING`；
- Artifact 与 SHA-256：`PENDING`；
- 最终准备结论：`PENDING`。
