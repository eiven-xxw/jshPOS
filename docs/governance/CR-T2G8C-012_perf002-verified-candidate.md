# CR-T2G8C-012：T2-PERF-002 VERIFIED 候选

## 结论

两项既定性能 P1 已以三个性能平面完成代码和门禁装配：正式 MySQL/Redis/JAR/HTTP 并发持续与资源负载、Gate 7/8 Owner 容量负载、Flutter POS 扫码/结算/同步/10 万记录包负载。T2-PERF-002 更新为 `VERIFIED` 候选；同一候选提交的 GitHub 完整 CI 全绿是 `CONDITIONAL PASS` 成立条件。

## 本地证据

- 治理、RTM、契约、范围与 Python 执行器自测通过；
- 10 万条签名批次包在本地 Flutter/SQLite 正式实现中原子安装，记录数与 ACTIVE 指针核对通过，观察耗时约 8.1 秒；
- 没有生产业务代码、数据库迁移或依赖变化；没有新增业务能力；
- 正式 MySQL/Redis/JAR 的 120 秒持续运行、尾延迟、资源和依赖暂停只能在干净 Linux CI 形成可比较证据，不能由本地结果替代。

## 边界

本结论最高为 `INTERNAL_FULL_STACK_PERFORMANCE_CANDIDATE`，不是生产容量、真实设备性能或商业 SLA。T2-RDY-001 继续 DRAFT；外部四项、UAT/REL、LIC/JSH 和全部零执行边界保持不变。
