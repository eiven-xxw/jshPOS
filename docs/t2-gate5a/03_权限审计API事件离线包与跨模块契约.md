# Gate 5A 权限、审计、API、事件、离线包与跨模块契约

> 文档编号：JSH-POS-T2-G5A-003

## 1. 权限与职责分离

| 权限 | 用途 | 数据范围 |
|---|---|---|
| `promotion:rule:create` | 创建规则身份和草稿版本 | 租户 |
| `promotion:rule:validate` | 预检结构、样例与能力 | 租户 |
| `promotion:rule:approve` | 审批待发布版本 | 租户/门店范围 |
| `promotion:rule:publish` | 发布、计划生效、暂停/撤销 | 租户/门店范围，审批人与发布人分离 |
| `promotion:quote:calculate` | POS/后台试算 | 可信门店范围 |
| `promotion:manual:authorize` | 阈值内应用或创建超阈值待复核人工优惠 | 可信租户与门店范围；请求体不得声明审批人 |
| `promotion:manual:approve` | 复核超阈值人工优惠 | 可信租户与门店范围；当前认证主体必须不同于操作人 |
| `promotion:manual:audit:read` | 查询人工优惠只追加事件 | 租户/组织/门店数据范围 |
| `promotion:snapshot:freeze` | 冻结成交优惠快照 | 可信门店和订单范围 |
| `promotion:refund:read` | 查询原快照可退额度 | 可信门店和原单范围 |
| `promotion:audit:read` | 查询促销审计 | 租户/组织数据范围 |

前端路由权限不能代替服务端授权。发布、人工优惠和退款恢复必须在应用服务再次校验可信 principal、门店数据范围和职责分离。

## 2. API 与错误语义

正式 OpenAPI 位于 `contracts/t2/gate5a/openapi-promotion-v1.yaml`，包括规则创建/预检/审批/发布/暂停、规则包获取、报价、人工优惠申请与独立复核、快照冻结和退款恢复查询。人工优惠申请显式携带 `authorizationId`、支付方式和当前报价指纹，复核请求只携带待复核结果指纹；任何请求 DTO 都不接收可作为授权依据的 tenant_id 或 approver_id。

错误码域：

- `PRM-RULE-*`：规则结构、版本、状态与复杂度；
- `PRM-CAPABILITY-*`：未准入算子、会员、券、预算或外部条件；
- `PRM-AMOUNT-*`：负数、溢出、金额不守恒与价格下限；
- `PRM-AUTH-*`：权限、阈值、审批人和职责分离；
- `PRM-IDEMP-*`：重复、同键异内容和原结果摘要损坏；
- `PRM-PACKAGE-*`：租户/门店不匹配、摘要、签名、过期和引擎不兼容；
- `PRM-SNAPSHOT-*`：冻结冲突、篡改和订单不一致；
- `PRM-REFUND-*`：原单不存在、数量/金额超限和累计冲突。

## 3. 事件

正式事件为 `promotion.rule.published.v1`、`promotion.rule.paused.v1`、`promotion.package.published.v1`、`promotion.quote.calculated.v1`、`promotion.manual.changed.v1`、`promotion.snapshot.frozen.v1`、`promotion.refund.allocation-recorded.v1`。人工优惠的申请、待复核和批准分别与命令结果、只追加审计、Outbox 在同一服务端事务中提交。事件使用稳定 event_id、聚合版本、payload hash、correlation_id 和可信 tenant_id；消费者至少一次接收、Inbox 防重。

事件只携带必要标识、版本、摘要和金额汇总，不携带生产密钥、真实会员敏感信息、完整规则签名私钥或支付报文。

## 4. 离线规则包

规则包包含：`tenantId`、`storeId`、`packageVersion`、`promotionReleaseVersion`、`engineCompatibility`、`schemaVersion`、规则 AST、适用索引、人工优惠策略版本/canonical JSON/SHA-256、业务时区、有效期、撤销版本、manifest SHA-256 和平台签名。

POS 安装顺序：验证载荷摘要和可信签名 → 验证可信设备 tenant/store → 验证 Schema/引擎能力 → 规范化规则与人工策略并分别复算摘要 → 在 SQLite 同一事务写入非活动槽和包绑定策略 → 原子切换 active slot。任何失败都不能改变活动指针；活动包过期、撤销、缺少人工策略或包含未支持算子时促销失败关闭并显示明确原因。

生产签名和密钥轮换不在本 Sprint；CI 仅使用固定合成测试密钥并标记 `SYNTHETIC_TEST_KEY`，不得作为商业验收证据。

## 5. 跨模块契约

- Catalog/Price 向促销提供只读 `VerifiedPricingInput`，包含可信 SKU、分类、品牌、单位、数量精度、基础/门店价与版本。
- Promotion 向 Order 提供 `PromotionTransactionSnapshot` 和摘要；Order 只在成交事务中冻结引用，不允许促销直接修改订单 Mapper。
- Promotion 向 Refund 提供只读 `PromotionRefundSnapshotPort`；Refund 提交原单行、退货数量和幂等键，促销返回本次可恢复金额并追加累计账本。
- Sync 只传输版本化包和快照事件，不成为规则、报价或分摊唯一事实源。
- Redis 只缓存 `tenant + store + releaseVersion` 的不可变包，不作为规则事实源。
