# RPT-PAY-REC 精确整改启动评审报告

## 当前结论

当前为 `PREP_VERIFIED_AWAITING_SPONSOR_CONFIRMATION`。候选提交的独立 CI 已完整通过，
准备范围、红基线、候选设计与停止线具备可重复证据；正式运行时仍未获准。

`G10A-SQL-P2-001` 继续 `OPEN`，`G10A-RES-P2-001` 继续 `PREPARED`。本报告不关闭 Finding，
不授权运行时、索引、迁移或 Provider 网络。

## 已完成准备范围

- 冻结 v1 API、权限、应用服务、Mapper statement、排序和 SQL 摘要；
- 冻结无界列表、50 门店导出、500 引用 501 查询和 filesort 红基线；
- 建立 MySQL 8.4.11 10k/100k 计划、查询数、租户攻击与十二项差异守恒采集；
- 设计 Reporting 投影批量端口与 Payment Provider 无关事实批量端口；
- 冻结 v2 keyset、HMAC 游标、快照漂移失败关闭和受控流式导出恢复；
- 建立九个固定失败 seed、量化验收、影响、兼容和回退边界；
- 形成三类索引比较方案，但索引/迁移授权仍为 0；
- 保持生产 Java/SQL/Mapper/API/事件/依赖/数据库对象/迁移变化为 0。

## 关键风险与建议

当前无界列表、逐门店导出和逐引用读取是确定的资源与查询放大风险。直接替换 v1 会扩大兼容风险，
建议保留 v1 并独立增加 v2 keyset。对账处理状态可变，不能只靠最后主键声称稳定快照；运行时必须
验证范围摘要/检查点漂移失败关闭。若现有对象不足以证明快照一致性，必须单独 CR，而不是弱化验收。

现有索引与全局 keyset 顺序不完全一致，但准备阶段不能据此直接新增 V90。只有获准运行时后，在
正式候选 SQL 上完成 10k/100k 可执行对比，才能判断是否提交独立索引 CR。

## Go/No-Go

- 当前运行时：`NO-GO`，等待项目发起人确认本报告；
- 本报告建议：`CONDITIONAL GO`，只准入 RPT-PAY-REC 运行时精确整改；
- 索引/迁移、Provider 网络、真实账单：`NO-GO`，均需独立确认；
- RES、R3、完整 Alpha、生产发布与外部执行：`NO-GO`。

## 已回填证据

- 候选 commit：`f895e540ba771e83cfc28c199633192618ccb9b5`；
- GitHub Run：[`32999443460`](https://github.com/eiven-xxw/jshPOS/actions/runs/32999443460)，
  总耗时 `10m12s`，五个 Job 全绿：`governance-ubuntu`、`governance-windows`、
  `test-baseline`、`mysql84`、`evidence`；
- MySQL：`8.4.11`，Schema `202608260089`，10k/100k 各执行一次正式冻结 SQL；均未观察到全表扫描，
  均观察到 `filesort`，分别返回 `4,800/48,000` 行；`crossTenantRows=0`；
- 查询放大红基线：500 个支付/退款引用为 `501` 次 JDBC 查询，50 门店旧导出为 `50` 次查询；
- 十二项差异守恒：10k/100k 均通过；100k 样本为内部/账单各 `48,000` 行、匹配 `45,000`、
  差异/OPEN 各 `3,000`、净差额 `300,000` 最小货币单位；
- MySQL Artifact：`9618262270`，摘要
  `sha256:0be3384846c2818ed5f1f19b7199c8851702111739dcb798b340c242628b6a7f`；
- 汇总证据 Artifact：`9618271347`，摘要
  `sha256:6720ce63a470cbaee72cef4fc509300dcde79bfb34243e785fd09d8dd1988b30`；
- 治理 Ubuntu/Windows Artifact：`9617907738` / `9617912998`；测试基线 Artifact：`9618104132`；
- 最终准备结论：`PREP_VERIFIED_AWAITING_SPONSOR_CONFIRMATION`。

以上只证明内部准备与红基线可重复，不代表性能达标或生产容量。`filesort`、无界结果、
50 次旧导出和 501 次引用读取是后续运行时批次必须关闭的红项；如正式候选 SQL 仍需索引，
必须停止并单独提交索引 CR 与唯一前向迁移方案。
