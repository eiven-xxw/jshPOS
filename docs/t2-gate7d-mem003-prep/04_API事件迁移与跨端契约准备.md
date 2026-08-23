# T2-MEM-003 API、事件、迁移与跨端契约准备

## 1. 契约状态

本阶段所有 API、事件和持久化内容均标记 `DRAFT / NON_EXECUTABLE`，用于评审字段、主权、错误码、
兼容和验收。它们不得生成 Controller、数据库表或客户端运行时。

## 2. DRAFT API

| API | Owner | 权限 | 说明 |
| --- | --- | --- | --- |
| `POST /api/v1/member-benefit-policies` | Member | create | 创建 DRAFT 策略，不接收 tenant_id |
| `POST /.../{version}/validate` | Member | validate | 完整预检和内容摘要 |
| `POST /.../{version}/approve` | Member | approve | 与创建人职责分离 |
| `POST /.../{version}/publish` | Member | publish | 未来生效和门店范围 |
| `POST /.../{version}/pause|revoke` | Member | pause/revoke | 不回写历史成交 |
| `POST /api/v1/member-benefit-quotes` | Promotion | quote | 接收不可逆权益快照引用，不接收 PII |
| `GET /api/v1/member-benefit-policies/{id}` | Member | read | 可信租户和门店数据范围 |

所有写操作要求 `Idempotency-Key`、`X-Correlation-Id` 和服务端可信上下文；错误响应使用现有正式
错误结构，不回显敏感载荷。

## 3. 错误码冻结

- `MEM_BENEFIT_CAPABILITY_DISABLED`
- `MEM_BENEFIT_NOT_APPLICABLE`
- `MEM_BENEFIT_VERSION_EXPIRED`
- `MEM_BENEFIT_REVOKED`
- `MEM_BENEFIT_SCOPE_DENIED`
- `MEM_BENEFIT_PACKAGE_INVALID`
- `MEM_BENEFIT_CLOCK_UNTRUSTED`
- `MEM_BENEFIT_STACKING_DENIED`
- `MEM_BENEFIT_QUOTE_STALE`
- `MEM_BENEFIT_CONTENT_MISMATCH`
- `IDEMPOTENCY_CONTENT_MISMATCH`

前六类默认失败关闭。普通销售能否继续必须由 UI 显式确认并重新生成非会员报价，不可复用原请求。

## 4. 事件

拟定事件：`member.benefit.version.changed.v1`、`member.entitlement.snapshot.changed.v1`、
`pricing.member-price.version.changed.v1`、`promotion.member-benefit.quoted.v1` 和
`order.member-benefit.frozen.v1`。Envelope 继承 eventId、可信 tenant context、schemaVersion、
occurredAt、payloadSha256、correlationId；事件体禁止 PII。

重复事件同摘要返回原结果，同 eventId 异摘要隔离；乱序按版本、effectiveAt 和 Owner 单调序列
收敛，禁止按到达时间覆盖。

## 5. 持久化设计登记

计划新增对象见 `persistence-design-registry.csv`。权益策略头允许 `CRUD_ENTITY + HYBRID`，版本发布
控制面使用 `CONTROLLED_WRITE + HYBRID`，状态/审计/Outbox/成交绑定为 `APPEND_ONLY + XML`，
复杂列表和时间范围解析为 `READ_PROJECTION + XML`。所有 SQL 显式携带可信 tenant_id 和数据范围。

正式实现前必须确定当时最新 MySQL/Flyway 与 SQLite 版本号；本准备阶段不预占迁移号、不新增 SQL。
已发布 `prc_price_book` 范围约束不得原地修改，必须通过前向模型扩展；现有 POS member cache 只能
用前向 Schema 扩展。

## 6. 跨端模型

Java 与 Dart 共享：状态枚举、错误码、金额整数、精确数量字符串、benefit/member-price/promotion
版本引用、叠加策略、路径选择、逐行 adjustment、订单合计和 fingerprint。JSON 规范化必须规定
字段排序、UTF-8、无多余空白、十进制普通表示和 SHA-256。

客户端只通过既有 Application Service、Repository 与 DataPackage 安装端口使用能力，禁止直接
访问 SQLite、MethodChannel 或手工拼装权益/订单事实。Vue 只调用 Owner API，不复制报价算法。

## 7. 迁移与容量验收准备

- 空环境安装、旧 Schema 升级、重复迁移、迁移中断与安全前向修复；
- 100k 会员价项、1k 门店范围、100 等级和多版本并存的合成索引验证；
- 权益包构建/校验/原子切换与投影重建；
- 旧客户端/新服务端、新客户端/旧服务端兼容矩阵；
- 跨租户、跨门店、缓存、任务、导出和对象存储攻击。

这些只属于未来运行时验收输入，不构成已验证容量或生产 SLA。
