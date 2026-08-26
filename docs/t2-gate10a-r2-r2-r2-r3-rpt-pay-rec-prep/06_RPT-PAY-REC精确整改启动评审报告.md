# RPT-PAY-REC 精确整改启动评审报告

## 当前结论

当前为 `PREP_IN_PROGRESS_RUNTIME_NOT_ADMITTED`。独立 CI 完整通过并回填候选提交、Run、Job、
Artifact 与 MySQL 指标后，最高可建议 `PREP_VERIFIED_AWAITING_SPONSOR_CONFIRMATION`。

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

- 当前运行时：`NO-GO`，等待完整 CI 与项目发起人确认；
- CI 全绿后的建议：`CONDITIONAL GO`，只准入 RPT-PAY-REC 运行时第一批；
- 索引/迁移、Provider 网络、真实账单：`NO-GO`，均需独立确认；
- RES、R3、完整 Alpha、生产发布与外部执行：`NO-GO`。

## 待回填证据

- 候选 commit：`PENDING`；
- GitHub Run：`PENDING`；
- MySQL 10k/100k、50/501 查询与守恒：`PENDING`；
- Artifact 与 SHA-256：`PENDING`；
- 最终准备结论：`PENDING`。
