# T2-MEM-003 DataPackage、SQLite、Flutter 与 Vue 独立验证记录

## 结论

本段记录为 `VERIFIED_LOCAL`，允许按批准的串行计划进入 Java/Dart 共用向量、故障注入、
内部 E2E 和完整 CI 收口。本结论不是整体 `T2-MEM-003 VERIFIED`。

## 已完成

- Member 与 Pricing 只通过正式只读端口向 Promotion 提供已发布、无 PII 的权益和会员价；
- Promotion 生成门店绑定、严格连续、SHA-256、Ed25519 签名的 canonical 离线包，并在同一事务
  追加包元数据、审计与 Outbox；
- MySQL V80 仅新增不可变包元数据；SQLite v16 只前进迁移新增 A/B 包槽、权益等级、会员价、
  报价权益绑定和订单权益快照；
- POS 安装器验证摘要、签名、租户、门店、版本连续性、有效期后原子切换，损坏、旧包、跨租户包
  和切换故障均失败关闭；
- 正式收银组合根支持短期令牌识别脱敏会员、服务端规则包报价、权益路径展示、成交同事务冻结，
  成交/取消/挂单后清除当前顾客身份；令牌与 PII 不进入小票、页面状态或成交快照；
- Vue 运营旅程按 Member 权益版本 → Pricing 会员价版本 → 无 PII 签名包串行操作，只调用正式 API，
  不在前端计算会员价、促销或退款。

## 本地验证

- Server 定向 12 项通过，Promotion 依赖反应堆编译通过；
- Flutter analyze 通过，会员权益、SQLite 原子成交、Controller 与 Widget 定向 33 项通过；
- Admin ESLint 通过，API/页面边界定向 4 项通过；
- 干净 MySQL、Java/Dart 共用 40 向量、内部 E2E 和完整 Gate CI 留待最终收口。

## 证据边界

所有结果均为 `INTERNAL_SYNTHETIC_SOFTWARE_ONLY`。支付沙箱、真实资金、真实设备/外设、伙伴现场、
完整 Alpha、生产与商业验收仍为 0；任何外部 `BLOCKED/DEFERRED` 状态均未改变。
