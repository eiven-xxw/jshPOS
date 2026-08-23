# ADR-052：Operations Owner 统一异常案件与 Owner 修复编排

- 状态：Accepted
- 日期：2026-08-23
- 决策范围：T2-EXC-001

## 决策

继续复用 `jshpos-operations`，由其独占异常案件、只追加来源观察、认领租约、转派、
处置计划、修复命令引用、独立复核、状态历史、审计和 Outbox。异常来源仍由 Sync、
Catalog/DataPackage、Payment/Refund、Inventory、Costing、Reporting 与 DailyClose 各自
Owner 独占；Operations 只能经显式窄端口或版本化事件读取和命令，不得依赖其他 Owner
Mapper，也不得更新任何来源事实。

案件采用稳定来源身份和内容摘要去重。同来源同摘要返回原案件；同来源异摘要追加观察
并标记冲突。认领采用租约、乐观锁和职责分离；关闭必须具备 Owner 可验证结果与不同
人员复核。修复命令保存稳定幂等键、请求摘要和 Owner 结果摘要，超时或 ACK 丢失只能
观察原命令。支付/退款 UNKNOWN 禁止生成新的扣款或退款命令。

Owner 没有安全修复能力、Provider/设备尚未解阻或证据不足时返回 `UNAVAILABLE` 或
`WAITING_OWNER`，不得伪造成功。异常中心只管理处置流程，不重新计算资金、库存、成本、
报表或日结结果。

## 数据与实现边界

- 头、租约等可变控制面登记为 `CONTROLLED_WRITE + XML`；来源观察、状态历史、审计和
  Outbox 为 `APPEND_ONLY + XML`；列表和详情为 `READ_PROJECTION + XML`。
- 所有查询、任务、缓存、导出和对象路径必须使用可信租户与门店范围；客户端 tenant、
  严重级别、来源摘要、Owner 结果和关闭状态均不是权威输入。
- MySQL 只允许前向迁移；旧事件与历史案件不可删除或覆盖，重新出现只能重开或新建关联。
