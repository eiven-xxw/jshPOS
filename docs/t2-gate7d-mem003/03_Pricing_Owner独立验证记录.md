# T2-MEM-003 Pricing Owner 独立验证记录

## 结论

Pricing Owner 阶段记录为 `VERIFIED_LOCAL`，允许按串行计划进入 Promotion Owner。该结论不是
整体 T2-MEM-003 VERIFIED，也不代表干净 MySQL、跨端成交、完整 CI 或外部证据通过。

## 已完成

- 租户级和门店级会员价版本、只追加精确金额明细、创建/批准职责分离和未来生效窗口；
- 门店会员价优先于租户会员价，候选解析前必须验证 Member Owner 无 PII 权益快照；
- 同一门店、等级、SKU、单位和生效窗口冲突失败关闭；
- 正式 REST、应用服务、Member 查询端口、XML Mapper、V77 前向迁移、权限、审计和 Outbox；
- 同键同内容返回稳定结果、同键异内容拒绝，客户端 tenant 不参与授权判定。

## 验证结果

- `jshpos-foundation`：74/74；`jshpos-member`：44/44；`jshpos-catalog`：62/62；
- JaCoCo 既有行/分支阈值：通过；
- XML-only 复杂 SQL、显式 tenant 谓词、无跨 Owner 写入、只追加明细触发器：通过；
- 干净 MySQL 8.4 迁移：留待本 Gate 完整 CI，不创建绿色占位。

## 证据边界

仅为内部软件本地证据；Promotion、订单退款、数据包、POS、Web 尚未实现，Provider 网络、
真实资金、真实设备/外设、伙伴现场、完整 Alpha、生产与商业验收均为 0。
