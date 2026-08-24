# CR-T2G8C-008：SEC-002 接受与 MTN-001 可维护性整改准入

## 结论

`CONDITIONAL GO / IN_PROGRESS`。项目发起人接受 `T2-SEC-002 CONDITIONAL PASS`，并只授权从 `eb2a8fec7c102da2db291c34822a01cced768c5d` 串行关闭五项既定可维护性 P1。

## 影响分析

- 运行时：只改变依赖方向、契约权威标记、审计分类、SQL 承载位置和源文件组织；正式业务输入、输出与失败语义保持不变。
- 数据：不新增、不改写 MySQL/SQLite 迁移；不改变表、列、索引、数据主权或历史事实。
- API/事件：不新增端点或事件；历史草案改为机器可识别的非运行时证据，当前运行时契约保持唯一。
- 安全/租户：Service 权益校验、Foundation 可信租户查询和既有租户攻击门禁不得弱化。
- Flutter：不改公共应用服务契约、SQLite 事务和页面业务旅程，只缩小文件与职责变更半径。
- 依赖/发布：不引入新第三方依赖，不改变外部证据状态，不构成生产或商业发布准入。

## 串行准入

1. `G8C-MTN-P1-001`：Service 内层权益端口和 SaaS 适配器；架构测试禁止跨 Owner 应用服务导入。
2. `G8C-MTN-P1-002`：唯一当前 OpenAPI 与历史草案元数据；机器审计 method/path 和 operationId。
3. `G8C-MTN-P1-003`：迁移审计器识别 callback；非法命名和历史迁移保护测试继续失败关闭。
4. `G8C-MTN-P1-004`：锁定 SQL 迁入 XML；显式列、resultMap、可信租户和禁止 `SELECT *` 测试。
5. `G8C-MTN-P1-005`：结算应用服务和页面按职责拆分；既有 Java/Dart 向量、SQLite 故障和 Widget 回归必须保持通过。

前项定向验证失败时后项不得开始。五项全部关闭且完整 CI 全绿后，`T2-MTN-001` 最多更新为 `VERIFIED` 并等待项目发起人确认。

## 保留状态

`T2-PERF-002/T2-RDY-001` 保持 `DRAFT`；PAY/HWD/PRN/PAR 保持 `BLOCKED`；UAT/REL 保持 `DRAFT`；LIC/JSH 保持 `DEFERRED`；全部外部执行为零。
