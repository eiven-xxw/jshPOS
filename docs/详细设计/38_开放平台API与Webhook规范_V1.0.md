# 连接器型商业收银经营平台

## 开放平台 API 与 Webhook 规范 V1.0

> 文档编号：POS-DD-038  
> 文档状态：架构评审稿  
> 技术基线：Spring Boot/RuoYi-Vue-Plus + API Gateway + OAuth 2.0 + OpenAPI/JSON Schema  
> 适用范围：合作伙伴、商户自建系统、连接器、ERP/WMS、会员、数据与运营应用  
> 基线日期：2026-08-15

---

# 文档说明

## 1. 编写目的

本文定义开放平台的应用注册、租户安装、OAuth 授权、API 资源、版本、幂等、并发、分页、错误、限流、Webhook 订阅、签名、重试、回放、沙箱、审计和商业发布标准。

## 2. 设计目标

- 外部应用只能访问租户明确授权的门店、资源和动作。
- API 契约可机器生成、测试和兼容审计。
- 写操作可安全重试，不重复创建订单、支付、退款或库存效果。
- Webhook 可验证、可防重、可追踪、可重放。
- API 与内部领域模型解耦，避免暴露数据库表和若依内部接口。
- 生产、沙箱、测试凭证和数据严格隔离。
- 合作伙伴有完整的开发者门户、示例、变更日志和认证流程。

## 3. 标准基线

- HTTP API 使用 OpenAPI 3.1.2 和 JSON Schema 2020-12：[OpenAPI 3.1.2](https://spec.openapis.org/oas/v3.1.2.html)。
- 错误响应采用 RFC 9457 application/problem+json：[RFC 9457](https://datatracker.ietf.org/doc/html/rfc9457)。
- OAuth 2.0 遵循 RFC 9700/BCP 240 安全最佳实践，不以尚未正式 RFC 化的“OAuth 2.1”名称代替实现要求：[RFC 9700](https://datatracker.ietf.org/doc/rfc9700/)。
- JWT 验证遵循 RFC 8725，包括算法白名单、issuer/audience 校验和密钥敏捷：[RFC 8725](https://datatracker.ietf.org/doc/rfc8725)。
- Webhook 消息签名采用 RFC 9421 HTTP Message Signatures，并用 RFC 9530 Content-Digest 绑定正文：[RFC 9421](https://datatracker.ietf.org/doc/html/rfc9421)、[RFC 9530](https://datatracker.ietf.org/doc/html/rfc9530)。
- Webhook 事件字段采用 CloudEvents 1.0.2 核心语义。
- 安全测试覆盖 OWASP API Security Top 10 2023：[OWASP API Security](https://owasp.org/www-project-api-security/)。

---

# 一、开放平台边界

## 1.1 API 类别

| 类别 | 主要资源 | 默认开放 |
|---|---|---:|
| 主数据 | 商品、SKU、条码、门店、仓库 | 是 |
| 价格促销 | 价格簿、促销发布只读/受控写 | 受控 |
| 订单 | 查询、创建渠道订单、状态 | 是 |
| 库存 | 查询、预占、调整受限 | 是/受控 |
| 会员 | 查询/绑定/积分受限 | 高敏授权 |
| 支付退款 | 状态查询、受控退款 | 高风险 |
| 采购供应链 | 采购、收货、调拨 | 企业套餐 |
| 结算报表 | 结算单、经营摘要 | 企业套餐 |
| 设备 | 只读健康/远程命令 | 内部或认证伙伴 |
| Webhook | 订单、库存、支付、退款等事件 | 是 |

## 1.2 不开放

- sys_ 基础表和若依后台内部 API；
- 数据库 ID 自增策略、表结构或 Repository；
- 支付密钥、完整银行卡、PIN、支付授权码；
- 其他租户数据；
- 内部风控规则和密钥；
- 任意 SQL、脚本或文件系统访问；
- 未脱敏日志和原始敏感报文。

## 1.3 环境

- Sandbox：合成数据、独立域名、独立密钥。
- Staging/Certification：接近生产契约，伙伴认证。
- Production：正式租户与 SLA。

不同环境的 client_id、密钥、回调地址、Webhook endpoint 和数据不可互通。

---

# 二、应用、安装与授权

## 2.1 OpenApp

平台级应用：

- app_id ULID；
- client_id；
- app_name；
- owner/organization；
- app_type；
- redirect_uris；
- requested_scopes；
- webhook capability；
- data_classification；
- privacy_policy/terms URLs；
- status；
- certification_level；
- key metadata；
- support contact。

## 2.2 TenantInstallation

每个租户安装独立：

| 字段 | 说明 |
|---|---|
| installation_id | ULID |
| tenant_id | VARCHAR(20) |
| app_id | 应用 |
| granted_scopes | 授权 scope |
| data_scope | 门店、仓、品牌、组织 |
| installed_by | 授权用户 |
| status | ACTIVE、SUSPENDED、REVOKED |
| consent_version | 同意版本 |
| installed_at/expires_at | 生命周期 |
| token_policy | 访问令牌策略 |

应用不能用一个 token 横跨多个 tenant_id。平台运营身份使用独立内部通道，不伪装租户应用。

## 2.3 授权模式

| 场景 | 模式 |
|---|---|
| 商户用户安装 SaaS 应用 | Authorization Code + PKCE |
| 服务器到服务器 | Client Credentials + 租户安装授权 |
| 高安全伙伴 | private_key_jwt 或 mTLS |
| POS/设备 | POS-DD-035 设备授权，不复用开放应用 token |

禁止：

- Implicit Grant；
- Resource Owner Password Credentials；
- 把 client_secret 放在移动端；
- 长期不过期 bearer token；
- 通过 query string 传 access token。

## 2.4 Token

建议：

- access token 5—15 分钟；
- refresh token 轮换并检测复用；
- JWT 或不透明 token 均可，但资源服务器必须校验 issuer、audience、exp、nbf、scope、installation_id；
- JWT 明确算法白名单，不接受 alg=none；
- key 使用 kid，支持 JWKS 轮换；
- token 吊销、应用停用和租户撤权快速生效；
- 高风险写操作可要求 DPoP/mTLS 或短时 step-up。

## 2.5 Scope

命名：

resource.action 或 resource:action，项目统一选择冒号：

- products:read；
- products:write；
- inventory:read；
- inventory:reserve；
- orders:read；
- orders:write；
- payments:read；
- refunds:create；
- members:read_sensitive；
- settlements:read；
- webhooks:manage。

Scope 只表达动作，data_scope 再限制门店/仓/组织。二者同时满足才授权。

---

# 三、HTTP 与 URI

## 3.1 基础

- 仅 HTTPS。
- Production 基础路径：/openapi/v1。
- 资源名使用复数 kebab-case。
- JSON 字段使用 snake_case，与现有 Java/Dart 契约统一。
- URI 不暴露数据库表名。
- 路径只放资源标识，复杂查询使用 query。
- 动作用子资源或明确命令 endpoint，不滥用 RPC 动词。

示例：

- GET /openapi/v1/orders/{order_id}
- POST /openapi/v1/orders
- POST /openapi/v1/inventory-reservations
- POST /openapi/v1/refunds
- POST /openapi/v1/orders/{order_id}/cancellation-requests

## 3.2 HTTP 方法

| 方法 | 语义 |
|---|---|
| GET | 读取，无业务副作用 |
| POST | 创建资源或提交命令 |
| PUT | 全量替换，仅少数资源 |
| PATCH | JSON Merge Patch 或明确 Patch Schema |
| DELETE | 删除/撤销可删除资源，不删除交易事实 |

DELETE 交易订单、支付、退款和库存流水禁止；使用取消、关闭、冲正等命令。

## 3.3 Content-Type

- application/json；
- application/problem+json；
- application/merge-patch+json；
- 文件上传使用 multipart/form-data 或预签名对象存储；
- Webhook 事件 application/cloudevents+json 或约定 JSON。

所有 JSON 使用 UTF-8。

---

# 四、标识、金额、数量与时间

## 4.1 标识

- 内部资源 ID 为 ULID 字符串。
- tenant_id 为 VARCHAR(20)，不由客户端自由切换。
- 外部 ID 为字符串，最长 128，保留前导零。
- API 不返回数据库自增平台表 ID，除非它本身是公开稳定标识。
- 资源 ID 不包含租户、门店或日期的可解析敏感信息。

## 4.2 Money

~~~json
{
  "amount_minor": 12800,
  "currency": "CNY"
}
~~~

- amount_minor 为 64 位整数语义。
- JavaScript SDK 对超安全整数范围的字段可用字符串封装；Schema 明确 format。
- 单价/成本高精度字段使用 decimal string。
- 禁止 float/double 货币。

## 4.3 Quantity

数量使用 decimal string：

~~~json
{
  "value": "2.500000",
  "unit_code": "KG"
}
~~~

## 4.4 时间

- instant：RFC 3339 UTC，例如 2026-08-15T02:00:00.123Z。
- local_business_time：带 offset。
- business_date：YYYY-MM-DD。
- API 响应带 Date，重要写响应带 server_time。
- 客户端时间不用于权限、幂等和资金最终判断。

---

# 五、资源表示

## 5.1 资源元数据

~~~json
{
  "id": "01K...",
  "resource_version": 8,
  "created_at": "2026-08-15T01:00:00Z",
  "updated_at": "2026-08-15T02:00:00Z"
}
~~~

## 5.2 字段选择与展开

可以支持：

- fields=id,name,status；
- include=lines,payments；

但必须：

- 有白名单；
- 限制展开深度和大小；
- 敏感字段仍受 scope；
- 不允许任意表达式或数据库列名；
- OpenAPI 描述每个资源允许值。

## 5.3 空值

- 缺失表示未提供/不适用。
- null 表示明确未知/清空，仅在 Schema 允许。
- 空数组表示已知无元素。
- PATCH 中 null 的清空语义必须逐字段定义。

---

# 六、查询、分页与排序

## 6.1 Cursor 分页

响应：

~~~json
{
  "data": [],
  "page": {
    "next_cursor": "opaque...",
    "has_more": true,
    "limit": 100
  }
}
~~~

- cursor 不透明、有签名、带租户和查询摘要。
- 默认 limit 50，最大 200 或按资源配置。
- 同一 cursor 必须配合同一过滤和排序。
- cursor 过期返回 CURSOR_EXPIRED。
- 大数据导出使用异步 Job，不用无限分页。

## 6.2 过滤

- updated_after/updated_before；
- created_after/created_before；
- status；
- store_id；
- external_id；
- business_date；
- ids 批量但限制数量。

禁止客户端提交 SQL、字段运算或任意脚本。

## 6.3 排序

- 只允许白名单字段。
- 默认 updated_at ASC, id ASC 用于增量。
- 任何排序加稳定 ID 决胜。
- 不承诺多次分页期间没有数据变化；需要一致快照时使用 snapshot_token。

---

# 七、写入、幂等与并发

## 7.1 Idempotency-Key

所有创建订单、支付、退款、预占、库存调整和批量任务的 POST 必须要求：

Idempotency-Key: 业务意图唯一字符串

服务端保存：

- tenant_id、installation_id；
- endpoint/method；
- key；
- normalized_request_hash；
- response status/body 摘要；
- resource_id；
- first_seen_at/expires_at。

同键同请求返回原结果；同键不同请求返回 409 IDEMPOTENCY_KEY_REUSED。

## 7.2 Request ID

客户端传 X-Request-Id ULID；服务端总是返回 X-Request-Id 和 trace_id。Request ID 用于追踪，不替代业务幂等键。

## 7.3 乐观并发

资源响应带：

- ETag: "rv-8"；
- resource_version: 8。

修改关键资源要求 If-Match。版本不一致返回 412 PRECONDITION_FAILED，不采用最后写入覆盖。

## 7.4 异步操作

耗时操作返回 202 Accepted：

~~~json
{
  "job_id": "01K...",
  "status": "PENDING",
  "status_url": "/openapi/v1/jobs/01K..."
}
~~~

Job 状态 PENDING、RUNNING、SUCCEEDED、PARTIALLY_SUCCEEDED、FAILED、CANCELLED。结果分页并保留错误明细。

## 7.5 批量

- 每项有 client_item_id。
- 返回逐项 status/resource_id/problem。
- 批量 HTTP 成功不代表每项成功。
- 请求必须声明 atomic = true/false。
- 默认非原子，失败项可复用原 item idempotency key 重试。
- 交易资金类批量默认禁止或严格受控。

---

# 八、响应与错误

## 8.1 成功状态

| 状态 | 用途 |
|---|---|
| 200 | 查询/同步命令完成 |
| 201 | 资源创建 |
| 202 | 异步受理 |
| 204 | 无正文成功 |

201 返回 Location。

## 8.2 Problem Details

~~~json
{
  "type": "https://developers.example.com/problems/idempotency-key-reused",
  "title": "Idempotency key was reused with a different request",
  "status": 409,
  "detail": "Use the original request or provide a new key.",
  "instance": "urn:request:01K...",
  "code": "IDEMPOTENCY_KEY_REUSED",
  "trace_id": "a8...",
  "errors": [
    {
      "pointer": "/amount/amount_minor",
      "code": "VALUE_MISMATCH"
    }
  ]
}
~~~

- 客户端只依赖 code、status 和结构化字段，不解析 detail。
- detail 不暴露堆栈、SQL、密钥或内部主机。
- type URI 有公开文档。
- 验证错误用 JSON Pointer 指向字段。

## 8.3 状态映射

| HTTP | 含义 |
|---|---|
| 400 | 请求语法/基础验证错误 |
| 401 | 未认证/Token 无效 |
| 403 | 已认证但 scope/data_scope 不足 |
| 404 | 不存在或对当前主体不可见 |
| 409 | 状态、幂等或唯一冲突 |
| 412 | If-Match 失败 |
| 422 | 业务语义验证失败 |
| 429 | 限流 |
| 500 | 内部错误 |
| 502/503 | 上游/服务不可用 |

跨租户资源返回 404，避免枚举。

---

# 九、限流、配额与防滥用

## 9.1 维度

- client_id；
- installation_id；
- tenant_id；
- IP/网络；
- endpoint/operation；
- 高成本资源；
- Webhook 测试和重放。

## 9.2 响应头

- RateLimit-Limit；
- RateLimit-Remaining；
- RateLimit-Reset；
- Retry-After。

最终头名在 API 网关实现和标准支持 ADR 中固化。

## 9.3 策略

- Token bucket + 突发额度。
- 读取和写入分池。
- 退款、库存调整等高风险动作更低额度。
- 429 不计为业务失败，SDK 遵循 Retry-After。
- API Key/应用被攻击时可独立封禁。
- GraphQL 非商业 V1 默认能力，避免复杂度滥用。

---

# 十、Webhook 订阅

## 10.1 WebhookSubscription

| 字段 | 说明 |
|---|---|
| subscription_id | ULID |
| tenant_id | VARCHAR(20) |
| installation_id | 应用安装 |
| endpoint_url | HTTPS 回调 |
| event_types | 事件白名单 |
| data_scope | 门店/仓等 |
| status | PENDING_VERIFICATION、ACTIVE、PAUSED、DISABLED |
| signing_key_id | 签名密钥 |
| filter | 受控过滤 |
| api_version | 事件版本 |
| failure_policy | 失败策略 |

## 10.2 Endpoint 验证

创建/改 URL：

1. 验证 HTTPS、DNS 和 SSRF 策略。
2. 发送 verification.challenge.v1。
3. 伙伴在限定时间返回 challenge。
4. 成功后 ACTIVE。
5. DNS/IP 变化触发持续 SSRF 防护。

禁止：

- localhost、link-local、云元数据地址；
- 私网地址，除非专线白名单；
- 非标准端口未经审批；
- 重定向到禁止地址；
- URL 含凭证。

## 10.3 事件格式

~~~json
{
  "specversion": "1.0",
  "id": "01K...",
  "source": "urn:pos:tenant:000001",
  "type": "com.pos.order.completed.v1",
  "subject": "orders/01K...",
  "time": "2026-08-15T02:00:00.123Z",
  "datacontenttype": "application/json",
  "dataschema": "https://developers.example.com/schemas/order-completed-v1.json",
  "tenantid": "000001",
  "correlationid": "01K...",
  "data": {}
}
~~~

## 10.4 事件目录

- product.changed.v1；
- price.release_activated.v1；
- inventory.changed.v1；
- order.created.v1；
- order.confirmed.v1；
- order.completed.v1；
- order.cancelled.v1；
- payment.succeeded.v1；
- payment.unknown.v1；
- refund.succeeded.v1；
- return.completed.v1；
- shift.closed.v1；
- connector.health_changed.v1。

只发布对外批准事件，不自动暴露所有内部领域事件。

---

# 十一、Webhook 签名

## 11.1 HTTP Message Signatures

每次投递至少绑定：

- @method；
- @target-uri；
- content-digest；
- content-type；
- x-webhook-id；
- x-webhook-timestamp。

Headers 示例：

~~~text
Content-Digest: sha-256=:base64digest:
X-Webhook-Id: 01K...
X-Webhook-Timestamp: 1786768800
Signature-Input: sig1=("@method" "@target-uri" "content-digest" "content-type" "x-webhook-id" "x-webhook-timestamp");created=1786768800;keyid="whk-2026-02";alg="ed25519"
Signature: sig1=:base64signature:
~~~

## 11.2 算法

- 优先 Ed25519 或合规批准的非对称算法。
- 如客户工具链限制，可提供 HMAC-SHA256 兼容档，但必须独立 secret、定期轮换。
- 算法白名单，不接受客户端自选任意算法。
- keyid 对应开发者门户 JWKS/公钥。
- 支持双密钥轮换窗口。

## 11.3 接收方校验

1. 使用原始 HTTP body 计算 Content-Digest。
2. 解析 Signature-Input。
3. 从可信 keyid 获取公钥。
4. 验证签名组件、算法和 created。
5. 校验 timestamp 在允许窗口。
6. 以 X-Webhook-Id/CloudEvent id 防重。
7. 成功落 Inbox 后快速返回 2xx。

不得先 JSON 重序列化再验证正文摘要。

---

# 十二、Webhook 投递

## 12.1 状态

PENDING → DELIVERING → DELIVERED / RETRY_WAIT / DEAD_LETTER / CANCELLED。

## 12.2 成功

- 2xx 表示接收方已可靠接收。
- 3xx 默认不跟随，防止 SSRF；伙伴需更新 endpoint。
- 4xx 除 408/409/425/429 外通常视为永久或需人工。
- 5xx/超时可重试。
- 返回正文不作为业务命令。

## 12.3 重试

建议：

- 0 秒；
- 30 秒；
- 2 分钟；
- 10 分钟；
- 1 小时；
- 6 小时；
- 24 小时；
- 最长 72 小时后死信。

实际按事件等级配置并加抖动。每次投递使用相同 event id，不生成新业务事件；delivery_attempt 递增。

## 12.4 顺序

- 不保证不同资源全局顺序。
- 同一 aggregate 尽力按 aggregate_version 顺序。
- 重试可能导致后版本先到，消费者必须处理 gap 或查询资源。
- Webhook payload 是通知/快照，关键业务应通过 GET 资源确认。

## 12.5 回放

开发者门户可按事件 ID、时间范围和订阅回放：

- 需要 webhooks:manage；
- 记录操作者和原因；
- 保留原 event id，新增 replay_id；
- 签名使用当前有效密钥；
- 限流；
- 不重新执行平台内部业务。

---

# 十三、API 版本与生命周期

## 13.1 版本

- 主版本在 URI：/v1。
- 向后兼容新增不升 URI。
- 单事件 type 自带 v1。
- OpenAPI info.version 表示文档版本。
- 响应可带 X-API-Version 和 X-Contract-Revision。

## 13.2 兼容变更

允许：

- 新增可选字段；
- 新 endpoint；
- 新可选 query 参数；
- 枚举新增但必须提供 UNKNOWN 安全策略；
- 放宽约束。

破坏性：

- 删除/重命名字段；
- 改类型或金额单位；
- 改必填；
- 改默认排序/分页语义；
- 改状态或错误码含义；
- 收紧无替代约束。

## 13.3 弃用

- 文档、响应 Deprecation/Sunset 头和门户通知。
- 重大版本至少提供约定迁移期。
- 提供迁移指南、差异清单、双版本沙箱。
- 监控仍使用旧版的应用并主动联系。
- 到期后先只读/限流或按政策关闭。

---

# 十四、开发者门户与 SDK

## 14.1 门户

- 应用注册与凭证；
- OAuth 安装；
- OpenAPI 下载；
- 事件目录和 JSON Schema；
- 在线/离线示例；
- Sandbox 数据生成；
- Webhook endpoint、密钥、测试和回放；
- API 用量、限流和错误；
- 变更日志和弃用；
- 认证申请与支持工单；
- 状态页。

## 14.2 SDK

优先提供：

- Java；
- JavaScript/TypeScript；
- Python；
- PHP（视客户群）。

SDK 负责：

- OAuth/token；
- 请求签名/追踪；
- Idempotency-Key 帮助；
- Cursor 分页；
- Problem Details；
- Retry-After；
- Webhook 验签；
- 类型模型。

SDK 不应自动重试所有 POST；只有明确幂等且保留原 key 的请求可重试。

## 14.3 示例

示例不得：

- 把 secret 写源码；
- 关闭 TLS 验证；
- 忽略签名；
- 使用固定生产 token；
- 用 float 计算金额；
- 把 Webhook 收到即视为已完成业务而不防重。

---

# 十五、安全

## 15.1 API Gateway

- TLS、WAF、Bot/DoS 防护；
- JWT/OAuth 校验；
- Scope 与安装上下文；
- 请求大小和 Content-Type；
- 限流和配额；
- IP allowlist/mTLS 可选；
- Request ID；
- 基础 Schema 验证；
- 日志脱敏。

领域授权仍在服务端应用层执行，不能只依赖 Gateway。

## 15.2 OWASP API 风险

必须覆盖：

- Broken Object Level Authorization；
- Broken Authentication；
- Broken Object Property Level Authorization；
- Unrestricted Resource Consumption；
- Broken Function Level Authorization；
- Sensitive Business Flows；
- SSRF；
- Security Misconfiguration；
- Improper Inventory Management；
- Unsafe Consumption of APIs。

## 15.3 数据最小化

- Scope 与字段级权限。
- 默认不返回会员手机号/地址。
- 订单联系人优先掩码/虚拟号。
- 导出和批量查询更严格授权。
- 应用撤权后停止新增访问并按协议处理缓存数据。
- 审计应用读取敏感字段。

## 15.4 安全事件

- token/secret 泄漏；
- 重放/签名失败激增；
- 跨租户尝试；
- 大规模枚举；
- 异常退款/库存调整；
- Webhook endpoint 劫持；
- 应用行为偏离声明。

支持单 installation、client、key、scope、tenant 的细粒度封禁。

---

# 十六、审计与合规

审计至少记录：

- tenant_id、installation_id、client_id；
- actor/sub；
- scope/data_scope；
- method、normalized path、resource ID；
- request_id、trace_id、idempotency key 哈希；
- status、error code、latency；
- 敏感字段访问标记；
- IP、mTLS/key id；
- Webhook event/delivery/replay；
- 授权、撤销、密钥轮换。

日志不保存 access token、secret、完整支付信息和非必要 PII。保留期按安全/隐私方案实施。

---

# 十七、SLO 与容量

## 17.1 API

商业 V1 建议：

- 核心读 API 可用性 99.9%；
- 核心写 API 可用性 99.9%，以服务月衡量；
- 普通读 P95 小于 300 ms；
- 普通写受理 P95 小于 500 ms；
- 复杂任务返回 202；
- 错误率和限流分应用可见。

## 17.2 Webhook

- 事件进入投递队列 P95 小于 30 秒；
- 首次投递 P95 小于 60 秒；
- 投递平台可用性 99.9%；
- 死信不丢，保留并可回放；
- 大规模订阅失败不影响核心交易。

SLO 不保证合作方 endpoint 可用。

---

# 十八、可观测性

## 18.1 指标

- api_request_total/latency/error；
- auth_failure_total；
- scope_denied_total；
- rate_limit_total；
- idempotency_replay/conflict；
- webhook_event_total；
- webhook_delivery_success_rate；
- webhook_retry/dead_letter；
- webhook_signature_failure_report；
- old_api_version_clients；
- sensitive_data_access_total。

## 18.2 日志与 Trace

- X-Request-Id 贯穿 Gateway、应用、Outbox 和 Webhook。
- W3C trace context 可作为内部追踪标准。
- 外部响应返回 trace_id，不暴露内部拓扑。
- 高基数 ID 不作为常规指标标签。

---

# 十九、错误码目录

| code | HTTP | 含义 |
|---|---:|---|
| AUTH_TOKEN_INVALID | 401 | Token 无效 |
| AUTH_SCOPE_INSUFFICIENT | 403 | Scope 不足 |
| DATA_SCOPE_DENIED | 403/404 | 数据范围不足 |
| RESOURCE_NOT_FOUND | 404 | 不存在/不可见 |
| VALIDATION_FAILED | 400/422 | 验证失败 |
| IDEMPOTENCY_KEY_REQUIRED | 400 | 缺幂等键 |
| IDEMPOTENCY_KEY_REUSED | 409 | 同键不同请求 |
| RESOURCE_VERSION_CONFLICT | 412 | If-Match 失败 |
| BUSINESS_STATE_CONFLICT | 409 | 状态不允许 |
| RATE_LIMITED | 429 | 限流 |
| CURSOR_INVALID | 400 | 游标非法 |
| CURSOR_EXPIRED | 410 | 游标过期 |
| PAYLOAD_TOO_LARGE | 413 | 正文过大 |
| UNSUPPORTED_MEDIA_TYPE | 415 | 类型不支持 |
| WEBHOOK_SIGNATURE_INVALID | 401 | 签名无效 |
| WEBHOOK_ENDPOINT_FORBIDDEN | 422 | endpoint 安全策略拒绝 |
| API_VERSION_UNSUPPORTED | 400/410 | 版本不支持 |
| SERVICE_TEMPORARILY_UNAVAILABLE | 503 | 暂不可用 |

---

# 二十、认证与发布

## 20.1 应用状态

DRAFT → REVIEWING → SANDBOX → CERTIFYING → PUBLISHED → SUSPENDED/RETIRED。

## 20.2 认证

- OAuth 正确流程和 redirect URI；
- Scope 最小化；
- BOLA/BFLA 测试；
- 幂等、并发和错误处理；
- 限流与退避；
- Webhook 验签、防重和快速 ACK；
- Token/Secret 安全存储；
- PII 最小化；
- SDK/依赖安全；
- 隐私政策、删除和支持流程；
- Sandbox 到生产切换；
- 事故联系人。

## 20.3 发布门禁

- OpenAPI/Schema lint；
- 兼容性 diff；
- Contract test；
- DAST/SAST/SCA/Secret scan；
- 负载和限流测试；
- Webhook 故障注入；
- Runbook、告警和状态页；
- 文档、示例和 changelog；
- Go/No-Go 审批。

---

# 二十一、验收测试

## 21.1 鉴权

1. Token 过期、issuer/audience 错误。
2. JWT alg 混淆和 kid 异常。
3. Scope 足但 data_scope 不足。
4. 跨租户资源 ID。
5. Client Credentials 未安装租户。
6. Refresh token 复用。
7. 应用撤权即时生效。
8. mTLS/private_key_jwt 密钥轮换。

## 21.2 API

- 同幂等键重复 100 次；
- 同键不同 body；
- ETag 并发修改；
- Cursor 伪造/过期/换过滤器；
- 批量部分失败；
- 大请求、深层 JSON、未知字段；
- Decimal 和 BIGINT 边界；
- 429/Retry-After；
- 202 Job；
- RFC 9457 字段和脱敏。

## 21.3 Webhook

- 签名有效、无效、旧 timestamp；
- Content-Digest 不匹配；
- key 轮换双签/双验证；
- endpoint 302 到内网；
- DNS rebinding；
- 2xx、4xx、429、5xx、timeout；
- 同事件重复和乱序；
- 72 小时重试与死信；
- 门户回放；
- 订阅 Scope/data_scope；
- 大规模失败隔舱。

## 21.4 安全

- OWASP API Top 10；
- SSRF 云元数据；
- 资源消耗；
- Mass assignment；
- 敏感字段过度返回；
- API inventory/旧版影子接口；
- Unsafe external API payload；
- 日志和监控 token 泄漏；
- 导出滥用；
- 退款/库存敏感业务自动化滥用。

---

# 二十二、研发交付清单

## 22.1 平台

- Developer Portal；
- App/Installation/Consent；
- OAuth Authorization Server/集成；
- API Gateway 策略；
- Scope/data_scope；
- Idempotency 与 ETag；
- RFC 9457 错误目录；
- Webhook subscription/delivery/signature/replay；
- Sandbox、认证、用量和状态页。

## 22.2 领域

- 专用 Open API DTO 和 Facade；
- 不直接暴露内部 Entity；
- 命令幂等；
- Outbox 外部事件筛选；
- 字段级权限和脱敏；
- 资源版本与查询快照。

## 22.3 DevSecOps

- OpenAPI/Schema Registry；
- 兼容性门禁；
- API inventory；
- SAST/SCA/DAST；
- Secret 和 key 轮换；
- WAF/限流；
- 审计、指标、告警；
- 灾备和密钥恢复演练。

---

# 二十三、商业 V1 决策摘要

1. 开放 API 与若依管理后台接口完全分离，使用 /openapi/v1。
2. OpenAPI 3.1.2 + JSON Schema 2020-12 是机器契约基线。
3. OAuth 2.0 实现遵循 RFC 9700；用户安装采用 Authorization Code + PKCE，服务端采用 Client Credentials/非对称认证。
4. 每个 token 绑定单一租户安装、scope 和 data_scope。
5. 写接口端到端 Idempotency-Key；关键修改使用 ETag/If-Match。
6. 错误使用 RFC 9457 Problem Details，不自创不一致错误外壳。
7. Webhook 使用 CloudEvents 语义、RFC 9421 签名和 RFC 9530 Content-Digest。
8. Webhook 至少一次投递，消费者必须防重并处理乱序。
9. Sandbox、认证、生产完全隔离；只有认证应用可上生产。
10. API 安全门禁覆盖 OWASP API Security Top 10，并维护完整 API inventory。

本规范批准后，URI、授权模式、Scope、Money/Quantity、幂等、分页、错误、Webhook 签名和版本生命周期成为商业 V1 冻结契约。
