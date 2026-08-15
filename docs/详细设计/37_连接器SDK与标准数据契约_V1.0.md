# 连接器型商业收银经营平台

## 连接器 SDK 与标准数据契约 V1.0

> 文档编号：POS-DD-037  
> 文档状态：架构评审稿  
> 技术基线：Java 21 + Spring Boot/RuoYi-Vue-Plus 模块 + DB Outbox/Inbox + JSON Schema  
> 适用范围：美团、抖音、京东、淘宝闪购、鲸熵汇、支付、配送、商品库及其他合作平台  
> 基线日期：2026-08-15

---

# 文档说明

## 1. 编写目的

本文定义连接器运行时、SDK 扩展点、标准业务对象、命令与事件、外部映射、授权密钥、幂等、限流、重试、死信、对账、沙箱和认证体系。任何外部平台，包括鲸熵汇，都作为连接器中心的一个连接器实现，不在 POS 核心领域内建立特殊分支。

## 2. 目标

- 核心领域不感知外部平台私有字段。
- 一个外部业务事实只落一次内部账。
- 新连接器复用授权、任务、映射、原文、监控和对账能力。
- 标准契约具有明确版本、金额、数量、时间和枚举语义。
- 外部平台限流、超时、重复、乱序和部分成功可恢复。
- 连接器可独立灰度、暂停、回放和降级。
- 商业 V1 只运行平台签名、审核过的连接器，不允许租户上传任意代码。

## 3. 标准基线

- 事件信封采用 CloudEvents 1.0.2 的核心属性语义，并加入 tenant_id、aggregate_version 等受控扩展：[CloudEvents specification](https://github.com/cloudevents/spec)。
- Payload Schema 使用 JSON Schema Draft 2020-12：[JSON Schema 2020-12](https://json-schema.org/draft/2020-12)。
- 消息驱动接口文档采用 AsyncAPI 3.1.x；工具不兼容时最低使用 3.0.0 并在 ADR 固化：[AsyncAPI 3.0.0](https://www.asyncapi.com/docs/reference/specification/v3.0.0)。
- HTTP 接口遵循 POS-DD-038 的 OpenAPI 3.1.2、RFC 9457 和鉴权规范。

---

# 一、连接器定位与边界

## 1.1 连接器类型

| 类型 | 典型对象 | 双向能力 |
|---|---|---|
| COMMERCE_CHANNEL | 美团、抖音、京东、淘宝闪购 | 商品、价格、库存、订单、履约、退款 |
| BUSINESS_NETWORK | 鲸熵汇、加盟/供应网络 | 商品、订货、履约、往来、结算 |
| PAYMENT | 聚合支付/支付机构 | 支付、退款、账单、对账 |
| DELIVERY | 即时配送/同城物流 | 运单、骑手、轨迹、签收 |
| CATALOG | 商品资料库 | 商品、条码、品牌、图片、质量 |
| ERP/WMS | 企业 ERP、仓储 | 商品、库存、采购、调拨、财务 |
| INVOICE/TAX | 电子发票/税务服务 | 开票、红冲、状态 |
| MESSAGING | 短信、公众号、企业消息 | 通知与回执 |

## 1.2 连接器不拥有

- 内部订单、支付、退款、库存和成本事实；
- 租户用户权限；
- POS 离线协议；
- 促销最终计算；
- 内部单号生成；
- 财务记账口径。

连接器拥有外部授权、映射、原始报文、同步游标、任务和外部结果。内部领域只接受标准命令。

## 1.3 鲸熵汇原则

- connector_code = JINGSHANGHUI 或最终批准代码。
- 使用与其他连接器相同的 con_connector_instance、con_external_mapping、con_raw_message、con_job、con_dead_letter。
- 标准能力差异通过 capability manifest 表达。
- 特有字段进入 extensions.jingshanghui，核心字段不得复制一套。
- 需要新增通用语义时先升级标准契约；不得以鲸熵汇专用表绕过。
- 详细接口可在后续《鲸熵汇连接器接口规格》说明，但不得违反本文。

---

# 二、运行架构

## 2.1 组件

~~~text
External Platform
      |
Webhook / Poll / API
      |
Connector Gateway
      |
Connector Runtime
  | Auth | Rate Limit | Retry | Mapping | Raw Message |
      |
Canonical Contract + Domain Command Bus
      |
Order / Product / Inventory / Payment / Settlement
~~~

## 2.2 模块

| 模块 | 职责 |
|---|---|
| connector-contract | 标准 DTO、Schema、枚举和事件 |
| connector-sdk | SPI、上下文、HTTP、签名、游标和测试工具 |
| connector-runtime | 生命周期、任务、调度、限流、重试和隔离 |
| connector-gateway | 回调入口、鉴权、原文持久化和路由 |
| connector-auth | OAuth/API Key/证书和密钥库 |
| connector-mapping | 外部 ID、编码与状态映射 |
| connector-ops | 实例、任务、死信、回放、暂停和对账工作台 |
| connector-{code} | 具体平台 Adapter |

## 2.3 部署

商业 V1 可随模块化单体部署，但必须逻辑隔离：

- 独立 module、线程池、HTTP client、限流器和指标；
- 不直接访问其他领域 Repository；
- 只通过应用服务/命令总线调用；
- 大批量、长耗时任务使用独立 Worker；
- 高风险或第三方依赖冲突的连接器可单独进程部署；
- 连接器故障不得拖垮 POS 核心 API。

---

# 三、连接器实例与生命周期

## 3.1 ConnectorDefinition

平台级定义：

- connector_code；
- display_name；
- connector_type；
- sdk_api_version；
- adapter_version；
- capability_manifest；
- auth_schemes；
- callback_paths；
- supported_regions；
- data_classification；
- required_scopes；
- release_status；
- support_owner。

## 3.2 ConnectorInstance

租户级安装：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | CHAR(26) | ULID |
| tenant_id | VARCHAR(20) | 租户 |
| connector_code | VARCHAR(64) | 定义代码 |
| instance_name | VARCHAR(128) | 显示名 |
| status | VARCHAR(32) | 生命周期 |
| authorization_id | CHAR(26) | 密钥引用 |
| scope_config | JSON | 门店、仓、品牌范围 |
| capability_config | JSON | 启用能力 |
| mapping_profile_id | CHAR(26) | 映射配置 |
| cursor_state | JSON | 各流游标 |
| adapter_version | VARCHAR(32) | 当前适配器 |
| record_version | BIGINT | 乐观锁 |

## 3.3 状态

| 状态 | 含义 |
|---|---|
| DRAFT | 尚未授权 |
| AUTHORIZING | 授权流程中 |
| ACTIVE | 正常运行 |
| DEGRADED | 部分能力失败 |
| PAUSED | 管理暂停 |
| AUTH_EXPIRED | 授权过期 |
| BLOCKED | 安全或合规阻断 |
| DISABLED | 已停用 |
| UNINSTALLED | 已卸载，保留审计 |

PAUSED/DISABLED 不删除映射、原文和任务。恢复后从保存游标继续。

---

# 四、Capability Manifest

## 4.1 能力

| 能力代码 | 方向 | 说明 |
|---|---|---|
| catalog.pull.v1 | 外到内 | 拉取商品 |
| catalog.push.v1 | 内到外 | 发布商品 |
| price.push.v1 | 内到外 | 发布价格 |
| promotion.push.v1 | 内到外 | 发布活动 |
| inventory.push.v1 | 内到外 | 发布库存投影 |
| order.receive.v1 | 外到内 | 接收订单 |
| order.ack.v1 | 内到外 | 接单/拒单 |
| fulfillment.update.v1 | 内到外 | 备货、配送、完成 |
| cancellation.receive.v1 | 外到内 | 取消请求 |
| refund.receive.v1 | 外到内 | 退款/售后请求 |
| refund.update.v1 | 内到外 | 退款结果 |
| settlement.pull.v1 | 外到内 | 账单/结算 |
| webhook.receive.v1 | 外到内 | 回调 |

## 4.2 限制

每个能力声明：

- contract_version；
- supported_operations；
- batch_limit；
- rate_limit；
- consistency_model；
- latency_sla；
- max_payload_bytes；
- pagination_model；
- callback_supported；
- polling_interval；
- partial_failure_semantics；
- extension_schema。

运行时不应以平台名称硬编码能力。

---

# 五、SDK SPI

## 5.1 主接口

~~~java
public interface ConnectorAdapter {
    ConnectorDescriptor descriptor();
    CapabilityManifest capabilities();
    AuthProvider authProvider();
    WebhookHandler webhookHandler();
    List<ConnectorOperationHandler<?, ?>> operations();
    HealthResult health(ConnectorContext context);
}
~~~

~~~java
public interface ConnectorOperationHandler<I, O> {
    String operationCode();
    Class<I> inputType();
    Class<O> outputType();
    OperationResult<O> execute(ConnectorContext context, I input);
}
~~~

## 5.2 ConnectorContext

只提供受控能力：

- tenantId、connectorInstanceId、traceId；
- CredentialProvider；
- ResilientHttpClient；
- MappingService；
- RawMessageStore；
- CheckpointService；
- IdempotencyService；
- DomainCommandClient；
- Clock；
- Metrics/Logger。

不提供 DataSource、EntityManager 或其他领域 Repository。

## 5.3 线程与资源

- SDK Handler 必须线程安全或声明实例生命周期。
- 禁止创建无限线程池。
- HTTP 请求使用运行时提供的 client。
- 禁止绕过代理、证书和审计自建网络连接。
- 大文件流式处理，设置大小上限。
- 每个调用尊重 deadline 和 cancellation。
- 运行时设置 bulkhead，单连接器积压不影响其他租户。

## 5.4 依赖

- 连接器只依赖 connector-sdk-api，不依赖实现模块。
- 第三方依赖锁版本并进入 SBOM。
- 禁止打包冲突的 Spring/日志/JSON 核心库。
- 适配器包需平台签名和完整性哈希。
- 商业 V1 由平台 CI 构建，不加载租户上传 JAR。

---

# 六、标准事件信封

## 6.1 CanonicalEvent

~~~json
{
  "specversion": "1.0",
  "id": "01K...",
  "source": "urn:pos:connector:meituan:instance:01K...",
  "type": "com.pos.order.received.v1",
  "subject": "orders/01K...",
  "time": "2026-08-15T02:00:00.123Z",
  "datacontenttype": "application/json",
  "dataschema": "https://contracts.example/schema/order-received-v1.json",
  "tenantid": "000001",
  "connectorinstanceid": "01K...",
  "correlationid": "01K...",
  "causationid": "external-event-123",
  "aggregateversion": 1,
  "data": {}
}
~~~

## 6.2 属性

- specversion 固定 1.0。
- id 为内部事件 ULID，外部事件 ID 单独保存。
- source 稳定标识连接器实例。
- type 使用反向域/平台命名并带 v1。
- tenantid 对应内部 tenant_id VARCHAR(20)。
- time 使用 UTC RFC 3339/ISO 8601。
- dataschema 指向版本化 Schema。
- data 的 Decimal 数量使用字符串，结算金额使用整数 minor。

## 6.3 原始报文

con_raw_message 保存：

- raw_message_id；
- tenant_id、connector_instance_id；
- direction；
- transport；
- external_event_id；
- headers 脱敏快照；
- raw body 加密对象地址；
- payload_hash；
- signature_result；
- received_at/sent_at；
- retention_class；
- parse_status；
- trace_id。

原文是取证材料，不直接作为内部业务对象查询源。

---

# 七、通用数据类型

## 7.1 标识

| 字段 | 类型 | 规则 |
|---|---|---|
| id | string ULID | 内部聚合 |
| tenant_id | string max 20 | RuoYi 租户编号 |
| external_id | string max 128 | 平台对象 ID |
| external_code | string max 128 | 平台业务编码 |
| mapping_id | ULID | 映射记录 |
| idempotency_key | string max 128 | 一次业务意图 |

外部 ID 不解析、不转换为数字、不作内部主键。

## 7.2 Money

~~~json
{
  "amount_minor": 12800,
  "currency": "CNY"
}
~~~

- amount_minor 为 JSON integer，范围受 BIGINT 限制。
- 单一 Money 不允许 currency 为空。
- 不接受 float 货币。
- 单价需要高精度时使用 decimal 字符串 unit_price = "12.345600"。

## 7.3 Quantity

~~~json
{
  "value": "2.500000",
  "unit_code": "KG",
  "base_value": "2500.000000",
  "base_unit_code": "G",
  "conversion_rate": "1000.000000"
}
~~~

## 7.4 时间

- instant 使用 UTC ISO 8601 带 Z。
- 外部本地时间同时保存原文、解析时区和 normalized_time。
- business_date 使用 YYYY-MM-DD。
- 客户端/外部平台时间不决定幂等顺序。

## 7.5 地址与联系人

- 标准地址分省市区、详细地址、经纬度和平台原文。
- 联系人电话按最小必要原则保存和脱敏。
- 配送给骑手的虚拟号按有效期管理。
- 连接器日志不得输出完整地址、手机号和姓名。

---

# 八、商品契约

## 8.1 CanonicalProduct

| 字段 | 必填 | 说明 |
|---|---:|---|
| product_id | 否 | 内部 SPU |
| external_product_id | 是 | 外部 ID |
| name | 是 | 商品名 |
| short_name | 否 | 小票/渠道短名 |
| category_ref | 是 | 标准/外部类目引用 |
| brand_ref | 否 | 品牌 |
| status | 是 | ACTIVE、INACTIVE、DELETED |
| skus | 是 | SKU 数组 |
| images | 否 | 图片资源 |
| attributes | 否 | 标准属性 |
| extensions | 否 | 命名空间扩展 |

## 8.2 CanonicalSku

- external_sku_id；
- sku_code；
- barcodes；
- unit；
- sales_attributes；
- list_price/unit_price；
- tax_category；
- stock_managed；
- quantity_scale；
- shelf_life；
- package_dimensions；
- status；
- revision。

## 8.3 商品同步

- 外部删除映射为 tombstone，不物理删除有历史引用的商品。
- 同一外部 ID 只映射一个内部对象。
- 条码冲突进入人工映射，不自动覆盖。
- 图片下载检查类型、大小、恶意内容和 SSRF。
- 外部 HTML 描述净化。
- 类目/属性映射有版本和生效时间。

---

# 九、价格与库存契约

## 9.1 PricePublication

- store_id/external_store_id；
- sku_id/external_sku_id；
- price_type；
- unit_price decimal 或 amount_minor；
- currency；
- effective_from/to；
- price_book_version；
- publication_version；
- operation UPSERT/DELETE。

渠道成交价仍由订单导入复核；发布成功不代表外部平台即时生效，必须保存回执版本。

## 9.2 InventoryPublication

~~~json
{
  "publication_id": "01K...",
  "store_ref": {},
  "sku_ref": {},
  "available_quantity": "18.000000",
  "unit_code": "EA",
  "inventory_version": 87,
  "effective_at": "2026-08-15T02:00:00Z",
  "sold_out": false
}
~~~

- 发布量来自 POS-DD-033 的渠道投影。
- 外部值不得回写内部 on_hand。
- 售罄高优先级。
- inventory_version 单调递增。
- 外部只支持整数时，向下取整并保存转换规则。

---

# 十、订单契约

## 10.1 CanonicalOrder

| 组 | 字段 |
|---|---|
| 标识 | order_id、external_order_id、external_order_no |
| 归属 | tenant_id、connector_instance_id、store_ref、channel |
| 时间 | placed_at、expected_time、business_date |
| 顾客 | customer_ref、masked_contact |
| 履约 | fulfillment_type、address、delivery |
| 行 | items、modifiers、gifts |
| 金额 | gross、discounts、fees、tax、payable、paid |
| 支付 | payment_summary，不包含敏感凭证 |
| 优惠 | platform/merchant/supplier sponsor allocations |
| 备注 | customer_note、merchant_note |
| 版本 | external_revision、contract_version |
| 扩展 | extensions.{connector_code} |

## 10.2 订单状态映射

外部状态先映射为标准渠道状态：

- RECEIVED；
- ACCEPTED；
- REJECTED；
- PREPARING；
- READY；
- DELIVERING；
- COMPLETED；
- CANCELLATION_REQUESTED；
- CANCELLED；
- AFTER_SALE_PROCESSING；
- CLOSED。

再由订单应用服务决定内部 order_status/fulfillment_status。连接器不得直接 UPDATE ord_order。

## 10.3 导入校验

- 外部订单 ID、门店映射和版本；
- 币种、金额守恒、行合计与总计；
- SKU/商品映射；
- 数量精度和单位；
- 优惠承担方；
- 支付状态摘要；
- 地址和隐私字段；
- 重复、版本倒退和取消竞态；
- 是否可自动接单。

无法映射商品时按租户策略：

- 阻断接单；
- 创建待映射临时商品行；
- 接单但进入异常工作台。

不得把未知商品静默映射到任意默认 SKU。

## 10.4 去重

订单业务唯一键：

tenant_id + connector_instance_id + external_order_id。

版本唯一键再包含 external_revision 或 external_event_id。重复相同 hash 返回原结果；相同版本不同 hash 进入冲突。

---

# 十一、履约、取消与退款

## 11.1 履约命令

- AcceptOrder；
- RejectOrder；
- StartPreparing；
- MarkReady；
- Handover；
- CompleteFulfillment；
- ReportOutOfStock。

每个命令使用内部 command_id 与外部 idempotency key，保存请求、响应和最终查询结果。

## 11.2 取消

CancellationRequest 包含：

- external_request_id；
- order_ref；
- requested_by；
- reason_code/text；
- requested_at；
- refund_expectation；
- item scope；
- external_deadline。

连接器把外部请求转换为标准命令；订单/履约领域根据当前状态审批。外部超时不允许本地直接伪造 CANCELLED。

## 11.3 退款

RefundRequest：

- external_refund_id；
- original_order/payment refs；
- amount Money；
- lines/quantities；
- reason；
- evidence refs；
- expected_return；
- platform_decision；
- revision。

资金退款进入 POS-DD-032 Refund 聚合，连接器只同步外部申请和结果。

---

# 十二、结算与对账

## 12.1 SettlementRecord

- statement_id/external_statement_id；
- period；
- order/payment/refund refs；
- gross_sales；
- platform_discount；
- merchant_discount；
- commission；
- delivery_fee；
- service_fee；
- refund；
- adjustment；
- net_settlement；
- currency；
- settlement_at；
- raw_line_ref。

## 12.2 导入

- 原文件/报文加密保存和哈希。
- 行唯一键防重。
- Money 使用 minor，百分比和精确单价用 Decimal。
- 映射到内部订单/支付/退款。
- 未匹配、金额差异、跨期和重复进入对账队列。
- 连接器不直接生成会计凭证，结算领域消费标准记录。

## 12.3 对账

至少比较：

- 订单数与成交额；
- 取消/退款；
- 优惠承担；
- 手续费和佣金；
- 配送费；
- 净结算；
- 到账记录；
- 未匹配外部单。

---

# 十三、映射

## 13.1 ExternalMapping

| 字段 | 说明 |
|---|---|
| tenant_id | 租户 |
| connector_instance_id | 实例 |
| object_type | STORE、SKU、ORDER、PAYMENT 等 |
| internal_id | 内部 ULID |
| external_id | 外部字符串 |
| external_code | 外部编码 |
| mapping_status | ACTIVE、CONFLICT、DISABLED |
| mapping_source | AUTO、MANUAL、IMPORT |
| valid_from/to | 有效期 |
| record_version | 版本 |

唯一约束：

- tenant + instance + object_type + external_id；
- tenant + instance + object_type + internal_id 在一对一对象上唯一。

## 13.2 映射变更

- 历史订单映射不因商品重新绑定而重写。
- 合并/拆分映射创建新 revision。
- 人工映射记录操作者、证据和影响预览。
- 映射批量导入先 dry-run。
- 禁止跨 tenant_id 复用映射。

---

# 十四、授权与密钥

## 14.1 AuthProvider

支持：

- OAuth 2.0 Authorization Code + PKCE；
- OAuth 2.0 Client Credentials；
- API Key；
- HMAC；
- mTLS；
- 厂商签名方案。

## 14.2 Credential Vault

- 数据库只保存 secret_ref 和非敏感元数据。
- 密钥存专用密钥管理服务或加密 Vault。
- secret 不进入日志、事件、错误或导出。
- 连接器运行时按最小权限短时取用。
- 支持版本、轮换、吊销和到期告警。
- 测试与生产凭证完全隔离。

## 14.3 授权范围

授权记录保存：

- 外部商户/门店；
- scopes；
- issued_at/expires_at；
- refresh 状态；
- key version；
- 最近验证；
- 数据处理目的和租户同意；
- 撤销原因。

---

# 十五、可靠性

## 15.1 Inbox/Outbox

- 外部回调先原文落 con_raw_message 和 Connector Inbox，再返回可接受 ACK。
- 内部命令与 Outbox 在领域事务内提交。
- 外发任务由 con_job 持久化。
- 外部响应后保存结果和 mapping/checkpoint。
- 相同 idempotency_key 不重复外部业务效果。

## 15.2 重试

| 错误 | 策略 |
|---|---|
| 连接超时/5xx | 指数退避 + 抖动 |
| 429 | 遵循 Retry-After |
| 401 | 单次刷新凭证后重试 |
| 403 | 不自动重试，检查授权 |
| 400/422 | 永久失败，进入修复 |
| 结果未知 | 使用原外部请求号查询 |
| 部分成功 | 按 item result 仅重试失败项 |

重试复用原 command_id/idempotency key。

## 15.3 限流

- 每实例、能力、外部商户独立令牌桶。
- 支持平台返回配额动态调整。
- 交易确认、售罄、退款优先于全量商品。
- 积压过大时合并可覆盖型发布任务，例如库存只发最新版本。
- 不合并订单、支付、退款等不可变事实。

## 15.4 熔断与隔舱

- 按 endpoint/operation 熔断，不一刀切整个平台。
- 线程池、连接池和队列按连接器隔舱。
- 熔断期间 Webhook 仍可落原文。
- 自动恢复先半开探测。
- PAUSED 不丢任务，按策略积压或转最终失败。

## 15.5 死信

死信保存：

- 原任务和事件；
- 请求/响应脱敏摘要；
- 尝试历史；
- 错误分类；
- mapping/schema/adapter 版本；
- 推荐操作；
- replay_count；
- 审批和处置。

回放必须复用原业务幂等键，并生成新 replay_command_id。

---

# 十六、Webhook 入口

## 16.1 处理顺序

1. 限制正文大小和 Content-Type。
2. 保存原始字节哈希。
3. 按平台规则验证时间戳、nonce、签名和来源。
4. 解析最小路由字段。
5. 识别 connector_instance。
6. 写 RawMessage + Inbox。
7. 返回平台要求 ACK。
8. 异步解析、映射并提交标准命令。

不得先执行订单业务再保存原文和防重。

## 16.2 回调路径

路径包含 connector_code 和不可猜测 instance routing key，但真正授权仍依赖签名/证书：

/callbacks/v1/connectors/{connector_code}/{routing_key}

不把 tenant_id 明文作为唯一鉴权依据。

---

# 十七、Schema 与版本

## 17.1 版本层次

- sdk_api_version；
- connector_adapter_version；
- capability contract_version；
- event type version；
- payload schema version；
- external_api_version；
- mapping_profile_version。

## 17.2 兼容

- 新增可选字段向后兼容。
- 删除、重命名、类型或语义变化提升主版本。
- unknown fields 默认忽略并保留扩展，但 must-understand 不支持时拒绝。
- 枚举新增时消费者必须有 UNKNOWN/UNMAPPED 安全分支。
- 金额和数量类型不得破坏性改变。
- Schema Registry 在 CI 检查兼容。

## 17.3 Extensions

~~~json
{
  "extensions": {
    "meituan": {},
    "douyin": {},
    "jingshanghui": {}
  }
}
~~~

- key 使用 connector_code 小写。
- 扩展不能改变核心字段语义。
- 扩展 Schema 也必须版本化。
- 三个以上连接器出现同一字段时，应评审提升为标准字段。

---

# 十八、可观测性

## 18.1 指标

- connector_operation_total；
- connector_success_rate；
- connector_latency_ms；
- connector_rate_limit_total；
- connector_retry_total；
- connector_unknown_total；
- connector_dead_letter_total；
- connector_inbox_lag；
- connector_job_backlog；
- mapping_conflict_total；
- auth_expiry_seconds；
- reconciliation_difference_minor。

标签控制 tenant、connector_code、operation、result，不以 order_id 作为高基数标签。

## 18.2 Trace

贯穿：

- external_event_id；
- raw_message_id；
- internal event_id；
- command_id；
- order/payment/refund ID；
- external request/response ID。

trace 不记录 secret 和完整敏感报文。

## 18.3 健康

连接器健康包含：

- AUTH；
- API；
- WEBHOOK；
- MAPPING；
- BACKLOG；
- RECONCILIATION。

总体状态是最严重子项，但工作台可展开原因。

---

# 十九、SDK 开发流程

## 19.1 创建

1. 登记 ConnectorDefinition 和数据分类。
2. 选择 capability。
3. 生成 Adapter 模板和 Schema。
4. 实现 Auth/Webhook/Operation handlers。
5. 完成 mapping、错误映射和限流。
6. 运行契约测试和外部沙箱测试。
7. 完成安全、许可证和隐私评审。
8. 灰度到内部租户/门店。
9. 生产 shadow/低流量运行。
10. 认证发布。

## 19.2 代码门禁

- 不跨域 Repository；
- 不明文 secret；
- 所有 HTTP 有 timeout；
- 所有业务写有幂等键；
- 所有外部 ID 当字符串；
- 金额不用浮点；
- 未知态有查询；
- 429 有限流；
- 日志脱敏；
- Schema 和契约测试通过；
- SBOM 和高危依赖扫描通过。

## 19.3 模拟器

SDK 提供 FakeConnectorServer：

- 成功；
- 429/5xx；
- 延迟/超时；
- 回调重复、乱序、签名错误；
- 分页重复/缺失；
- Token 过期；
- 部分成功；
- 状态晚到；
- 大报文和非法字段。

---

# 二十、连接器认证

## 20.1 级别

| 级别 | 含义 |
|---|---|
| DEV | 本地/沙箱开发 |
| SANDBOX_VERIFIED | 外部沙箱通过 |
| PILOT | 指定租户/门店试点 |
| COMMERCIAL | 商业发布 |
| SUSPENDED | 暂停 |
| RETIRED | 退役，只保留历史 |

## 20.2 COMMERCIAL 门禁

- 核心能力、错误和限流用例通过；
- 重复回调与幂等通过；
- 订单、金额、优惠和退款对账通过；
- 授权轮换和撤销通过；
- 回放和死信工作台可用；
- 监控、告警、Runbook 与责任人完整；
- 外部平台正式资质/应用审核完成；
- 数据处理协议和隐私评审完成；
- 试点期无未解决 P0/P1。

---

# 二十一、错误码

| 错误码 | 含义 |
|---|---|
| CONNECTOR_AUTH_EXPIRED | 授权过期 |
| CONNECTOR_AUTH_REVOKED | 授权撤销 |
| CONNECTOR_RATE_LIMITED | 外部限流 |
| CONNECTOR_TIMEOUT | 外部超时 |
| CONNECTOR_RESULT_UNKNOWN | 结果未知 |
| CONNECTOR_SCHEMA_INVALID | 报文不符合 Schema |
| CONNECTOR_SIGNATURE_INVALID | 签名错误 |
| CONNECTOR_MAPPING_MISSING | 缺少映射 |
| CONNECTOR_MAPPING_CONFLICT | 映射冲突 |
| CONNECTOR_EXTERNAL_CONFLICT | 外部版本/状态冲突 |
| CONNECTOR_PARTIAL_FAILURE | 批量部分失败 |
| CONNECTOR_CAPABILITY_UNSUPPORTED | 能力不支持 |
| CONNECTOR_ADAPTER_INCOMPATIBLE | Adapter/SDK 不兼容 |
| CONNECTOR_PAUSED | 实例暂停 |
| CONNECTOR_FINAL_REJECTED | 永久拒绝 |

---

# 二十二、验收测试

## 22.1 契约

- 每个 Schema 正反样例；
- 未知字段和枚举；
- ULID、tenant_id VARCHAR(20)；
- Money minor 与 Decimal 数量；
- 时间和时区；
- 扩展命名空间；
- event version；
- 相同输入稳定 payload_hash。

## 22.2 可靠性

1. 回调重复 100 次。
2. 回调先于 API 响应。
3. 外发成功但响应丢失。
4. Token 在请求中途过期。
5. 429 带/不带 Retry-After。
6. 批量 100 条部分成功。
7. 任务数据库提交后进程崩溃。
8. 消息中间件不可用。
9. 暂停一小时后恢复积压。
10. 死信人工修复并回放。

## 22.3 业务

- 商品全量/增量/删除；
- 价格定时生效；
- 库存售罄高优先级；
- 订单重复、改单、取消竞态；
- 缺映射商品；
- 平台/商户优惠承担；
- 部分退款和售后；
- 配送履约；
- 结算账单差异；
- 鲸熵汇与其他连接器并存且无特殊核心分支。

## 22.4 安全

- 签名伪造和重放；
- SSRF；
- 恶意图片/HTML；
- Secret 日志扫描；
- 跨租户 instance/mapping；
- 回调路由枚举；
- 大报文和压缩炸弹；
- 依赖高危漏洞；
- 未签名 Adapter；
- 权限 scope 超限。

---

# 二十三、商业 V1 决策摘要

1. 鲸熵汇与美团、抖音、京东、淘宝闪购一样，是连接器中心的普通连接器。
2. 核心领域只接受标准命令和事件，不识别平台私有 DTO。
3. 标准事件采用 CloudEvents 语义，Payload 使用 JSON Schema 2020-12。
4. tenant_id 为 VARCHAR(20)，内部聚合/事件使用 ULID，外部 ID 永远按字符串处理。
5. 金额使用 minor integer，高精度单价与数量使用 Decimal 字符串。
6. 回调先落原文与 Inbox，领域事务使用 Outbox；端到端幂等。
7. 连接器运行时统一提供授权、HTTP、限流、重试、映射、死信和监控。
8. 商业 V1 不允许租户上传任意 JAR，只运行平台签名和认证的 Adapter。
9. 外部未知结果必须查询，不把超时直接当失败。
10. 每个连接器需经过沙箱、试点和商业认证，并具备对账和退役方案。

本规范批准后，Capability、Canonical Contract、事件信封、SDK SPI、错误码、幂等和认证流程成为商业 V1 冻结契约。
