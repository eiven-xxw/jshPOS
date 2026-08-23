# T2-MEM-003 Order/Refund 独立验证记录

## 结论

Order/Refund 阶段记录为 `VERIFIED_LOCAL`，允许按批准的串行计划进入 DataPackage、SQLite、
Flutter POS 与 Vue 阶段。该结论不是整体 T2-MEM-003 VERIFIED。

## 已完成

- 扩展 Promotion→Order 明确只读端口，传递无 PII 的原权益快照、权益版本、会员价版本、
  路径、能力配置、解释链和内容摘要；没有暴露 Promotion Mapper；
- Order Owner 在原订单事务内写入 `ord_member_benefit_binding`，只保存原 Promotion 绑定，
  不执行会员价、促销或权益算法；
- V79 只前进迁移建立数据库不可更新/不可删除触发器和哈希、路径约束；
- Return Owner 继续只提交原成交促销快照 ID 与退货数量，Promotion Owner 只读原快照逐行
  分摊；等级降级、权益撤回、会员价或促销版本变化不会触发退款重算；
- UNKNOWN 退款仍复用原命令查询/观察收敛，不生成新退款命令。

## 本地验证

- Order 全量 64 项、Promotion 全量 67 项均已通过；定向 14 项订单/迁移/XML 测试已通过；
- 可信租户、同键异内容、路径/摘要失败关闭、原绑定写入和禁止跨 Owner SQL 策略通过；
- 干净 MySQL 8.4 和完整 Gate CI 留待最终收口，不创建绿色占位。

## 证据边界

POS 本地权益包、SQLite 原子成交、Flutter/Vue 旅程和跨端向量尚未完成。外部支付、真实
资金、真实设备/外设、伙伴现场、完整 Alpha、生产与商业验收均为 0。
