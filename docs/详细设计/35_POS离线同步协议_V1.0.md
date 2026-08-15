# 连接器型商业收银经营平台

## POS 离线同步协议 V1.0

> 文档编号：POS-DD-035  
> 文档状态：架构评审稿  
> 上游基线：《领域模型与数据库设计说明书 V1.0》  
> 客户端基线：Flutter Android POS + SQLite WAL + 可选 POS Edge Agent  
> 服务端基线：RuoYi-Vue-Plus 模块化单体 + MySQL 8.4 LTS + DB Outbox  
> 基线日期：2026-08-15

---

# 文档说明

## 1. 编写目的

本文定义 Android POS 在断网、弱网、进程崩溃、设备重启和云端短时不可用条件下的本地事务、数据包、增量游标、命令上传、事件下发、幂等、冲突、恢复、安全和验收协议。

协议目标：

- 断网不影响受控现金收银；
- 本地成交、支付、库存、班次、打印与同步事件原子落盘；
- 传输至少一次，但每个业务效果恰好一次；
- 客户端生成的订单与事件 ULID 回云后保持不变；
- 主数据和规则包通过签名、版本和原子切换防止半包；
- 冲突以领域规则解决，不使用最后写入覆盖；
- 设备被克隆、数据被篡改或事件哈希冲突时能够阻断和审计；
- 协议可向前兼容并支持终端分批升级。

## 2. 适用范围

- Flutter Android POS；
- Android 手持 PDA 和移动收银；
- 未来 Windows POS 的相同同步内核；
- POS Edge Agent 代理的本地硬件与数据包缓存；
- 云端 Device、Sync、Order、Payment、Inventory、Pricing 模块。

## 3. 不适用范围

- 银行卡离线授权协议；
- 支付渠道私有 SDK 的底层通信；
- 第三方平台连接器的数据契约；
- 数据仓库 CDC 与分析同步。

## 4. 依赖关系

- tenant_id、ULID、Outbox/Inbox、数据表和金额数量类型遵循 POS-DD-031。
- 订单、支付、退款状态遵循 POS-DD-032。
- 库存与预占遵循 POS-DD-033。
- 离线价格、促销和优惠快照遵循 POS-DD-034。
- 设备证书、硬件身份与安全存储由 POS-DD-036 补充。

---

# 一、设计原则

## 1.1 一套业务 ID

- 设备、订单、订单行、支付、班次、打印任务、命令和事件均使用 ULID。
- POS 离线创建的 ID 在云端原样保留，不转换为新主键。
- tenant_id 是 VARCHAR(20)，来自设备授权令牌；消息体 tenant_id 仅作一致性校验。
- 本地可读单号与主键分离，可包含设备短码和本地序号，但不能作为全局关系键。

## 1.2 至少一次传输，恰好一次业务效果

网络协议采用至少一次：

- 请求可超时；
- 客户端可重复上传；
- 服务端可重复响应；
- 下行事件可重复发送。

业务层通过 event_id、command_id、idempotency_key、payload_hash、Inbox/Outbox 和唯一键实现恰好一次效果。协议不得宣称网络本身 exactly-once。

## 1.3 领域冲突策略

不采用 Last-Write-Wins。冲突按对象处理：

- 历史成交事实：保留终端事实并标记差异；
- 主数据：云端权威版本；
- 草稿：按拥有设备和版本控制；
- 库存：云端账本权威，离线差量作为幂等命令回放；
- 促销：使用成交时签名规则包和快照复核；
- 支付：渠道/现金账本权威，UNKNOWN 不可覆盖；
- 配置：云端签名版本，原子切换。

## 1.4 客户端时间不可信

- POS 保存 occurred_at、local_timezone、clock_offset_estimate。
- 服务端保存 received_at。
- 事件排序依赖聚合版本、设备序列和服务端游标，不单独依赖客户端时间。
- 时钟漂移超过阈值时警告或阻断受时效影响的促销与证书操作。

---

# 二、组件与职责

## 2.1 POS 本地组件

| 组件 | 职责 |
|---|---|
| Local Domain Store | SQLite 交易表、主数据投影、规则包元数据 |
| Local Transaction Coordinator | 原子提交订单、现金支付、班次、库存差量、打印、Outbox |
| Sync Outbox Worker | 批量上传本地命令和事件 |
| Sync Pull Worker | 拉取主数据、配置、撤销清单和服务端命令 |
| Inbox Processor | 幂等应用下行事件 |
| Package Manager | 下载、校验、双槽安装与回滚数据包 |
| Connectivity Monitor | 判定 ONLINE、DEGRADED、OFFLINE、RECOVERING |
| Recovery Manager | 崩溃恢复、存储检查、积压修复 |
| Secure Store | 设备私钥、令牌、证书和密钥版本 |
| Print Queue | 本地小票任务，独立于交易成功状态 |

## 2.2 云端组件

| 组件 | 职责 |
|---|---|
| Device Service | 激活、授权、证书、封禁与能力 |
| Sync Gateway | 鉴权、限流、协议版本、压缩和路由 |
| Sync Inbox | 事件防重、哈希校验和处理状态 |
| Domain Command Router | 把离线命令送入订单、库存、班次等领域 |
| Change Feed | 生成租户/门店范围的增量事件与游标 |
| Package Builder | 构建主数据、价格、促销和配置包 |
| Outbox Publisher | 可靠投递领域事件 |
| Sync Operations Console | 设备健康、积压、冲突与人工修复 |

## 2.3 Edge Agent

Android 主路线不要求 Edge Agent。以下场景可启用：

- 厂商硬件仅提供本地 Socket、串口或 Windows 驱动；
- 多台 POS 共享门店打印、称重或本地缓存；
- 需要局域网数据包分发或断网门店代理。

Edge Agent 不拥有交易事实，只代理设备、缓存签名包和转发协议；不得生成第二套订单 ID 或绕过终端幂等。

---

# 三、连接状态

## 3.1 状态

| 状态 | 判定 | POS 行为 |
|---|---|---|
| ONLINE | API、鉴权、时间和关键依赖正常 | 完整在线能力 |
| DEGRADED | 网络可达但延迟/错误率超阈值 | 减少非关键请求，交易按能力矩阵 |
| OFFLINE | 无法在阈值内连接 Sync Gateway | 进入离线能力 |
| RECOVERING | 网络恢复，正在校时、推送和拉取 | 允许安全本地操作，限制敏感动作 |
| BLOCKED | 证书吊销、数据篡改、版本不兼容或存储风险 | 禁止交易，仅允许修复/重新激活 |

## 3.2 状态判定

- 不以一次 ping 失败立即转 OFFLINE。
- 建议连续 3 次失败或 10 秒无关键 API 可用转 DEGRADED/OFFLINE。
- 网络恢复后先取得服务端时间、设备状态和协议能力，再进入 RECOVERING。
- 推送关键 Outbox、拉取安全撤销和配置后才回 ONLINE。
- UI 必须显示状态、最后成功同步时间和积压数量。

---

# 四、设备激活与会话

## 4.1 激活

1. 后台创建设备授权码，绑定 tenant、store、terminal profile 和有效期。
2. POS 生成硬件支持的非导出密钥对。
3. POS 提交激活码、公钥、应用版本、硬件指纹摘要和能力清单。
4. 服务端验证一次性授权码，签发 device_id ULID、设备证书和刷新凭证。
5. POS 把密钥与证书存入 Android Keystore。
6. 服务端返回 bootstrap manifest。
7. 完成初始数据包校验和安装后设备转 ACTIVE。

激活码只能使用一次；重新安装、换主板或克隆检测需要重新授权。

## 4.2 请求头

| Header | 必填 | 说明 |
|---|---:|---|
| Authorization | 是 | 短期设备访问令牌 |
| X-Device-Id | 是 | 设备 ULID |
| X-Protocol-Version | 是 | 整数主版本，例如 1 |
| X-App-Version | 是 | POS 语义版本 |
| X-Schema-Version | 是 | 信封 Schema 版本 |
| X-Request-Id | 是 | 每次 HTTP 请求 ULID |
| X-Store-Id | 是 | 当前授权门店 |
| Content-Encoding | 否 | gzip 或 zstd，按能力协商 |

tenant_id 不由 Header 自由选择，服务端从设备会话获取。

## 4.3 会话与续期

- 访问令牌短期有效，刷新凭证绑定设备证书。
- 离线不要求令牌持续在线续期，但本地离线授权有单独 expires_at。
- 超过 offline_grace_period 后禁止新增交易，仅允许查看、导出和恢复。
- 设备封禁、密钥轮换和最低版本策略通过高优先级安全清单下发。

---

# 五、本地数据库

## 5.1 SQLite 设置

- 开启 WAL。
- foreign_keys = ON。
- synchronous 至少 NORMAL，高风险终端可用 FULL。
- busy_timeout 有明确值并监控。
- 每次启动执行 quick_check；异常时进入恢复模式。
- 数据库文件和敏感列按设备能力加密。
- Schema 使用前向迁移，迁移前做空间和电量检查。

## 5.2 本地核心表

| 表 | 用途 |
|---|---|
| local_order / local_order_line | 订单与快照 |
| local_payment | 现金支付和电子支付引用 |
| local_shift / local_cash_movement | 班次与现金流水 |
| local_stock_projection | 云端库存投影 |
| local_stock_pending_delta | 离线库存差量 |
| local_outbox | 待上传事件 |
| local_inbox | 已处理下行事件 |
| local_sync_cursor | 各流游标 |
| local_package_slot | 当前/备用数据包 |
| local_print_job | 打印队列 |
| local_idempotency | 本地命令结果 |
| local_device_state | 激活、时间偏差、能力和风险状态 |

## 5.3 Outbox 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| event_id | CHAR(26) | ULID 主键 |
| tenant_id | VARCHAR(20) | 本地授权租户 |
| stream | VARCHAR(64) | order.command、stock.command 等 |
| aggregate_id | CHAR(26) | 聚合 ID |
| aggregate_version | BIGINT | 本地版本 |
| event_type | VARCHAR(96) | 版本化类型 |
| payload_json | TEXT/BLOB | 规范化负载 |
| payload_hash | CHAR(71) | sha256:hex |
| status | VARCHAR(24) | PENDING、SENDING、ACKED、RETRY、FINAL_REJECTED |
| attempt_count | INT | 尝试次数 |
| next_attempt_at | DATETIME(3) | 下次上传 |
| created_at | DATETIME(3) | 本地 UTC 语义时间 |
| acked_at | DATETIME(3) NULL | 云端确认时间 |
| last_error_code | VARCHAR(64) NULL | 最近错误 |

## 5.4 本地成交事务

离线现金销售必须在一个 SQLite 事务内：

1. 创建或更新订单为 PENDING_PAYMENT。
2. 固化价格和促销快照。
3. 创建现金 Payment 并迁移 SUCCEEDED。
4. 订单迁移 CONFIRMED/COMPLETED。
5. 写班次现金收款和找零。
6. 写本地库存 pending_delta。
7. 创建打印任务。
8. 为订单、支付、库存和班次创建 Outbox。
9. 写本地幂等结果。
10. 提交事务。

提交后才打开钱箱和执行打印。打印失败不得回滚成交。

---

# 六、数据流与游标

## 6.1 同步流

| stream | 方向 | 内容 | 优先级 |
|---|---|---|---:|
| security.revocation | 云到端 | 设备封禁、证书、最低版本 | 1 |
| config.critical | 云到端 | 支付、离线额度、禁售配置 | 2 |
| order.command | 端到云 | 离线订单命令/事件 | 2 |
| payment.reference | 端到云 | 现金支付与电子未知引用 | 2 |
| shift.cash | 端到云 | 班次与现金流水 | 2 |
| stock.command | 端到云 | 销售出库与差量 | 3 |
| masterdata.product | 云到端 | 商品、条码、单位 | 4 |
| pricing.pricebook | 云到端 | 价格包 |
| promotion.release | 云到端 | 促销规则包 |
| inventory.projection | 云到端 | 门店库存投影 |
| receipt.template | 云到端 | 小票模板 |
| remote.command | 云到端 | 诊断、日志上传、刷新命令 |

同一聚合事件按 aggregate_version 有序；不同流和不同聚合不承诺全局顺序。

## 6.2 游标

- 每个 tenant_id + device_id + stream 保存独立 cursor。
- cursor 是不透明字符串，客户端不得解析或自增。
- 服务端游标单调推进，响应包含 next_cursor 和 has_more。
- 客户端只有在一批事件全部原子应用后才提交本地 cursor。
- ACK 丢失时服务端可重发，Inbox 保证不重复应用。
- 游标过旧或服务端日志已归档时返回 CURSOR_EXPIRED，并要求获取快照包。

## 6.3 设备序列

每个设备 Outbox 同时维护 device_sequence BIGINT：

- 在本地事务内严格递增；
- 仅用于检测缺口、诊断和单设备顺序；
- 不能替代 event_id 幂等；
- 服务端发现缺口可返回 ACCEPTED_WITH_GAP，并要求设备补传；
- 确认永久丢失必须人工登记，不允许伪造事件补号。

---

# 七、协议接口

## 7.1 Bootstrap

POST /api/pos/v1/sync/bootstrap

请求包含设备能力、当前应用/协议/Schema 版本、已安装包版本和本地游标。响应包含：

- server_time；
- device_status；
- tenant/store 授权摘要；
- min_app_version、recommended_app_version；
- supported_protocol_versions；
- offline_policy；
- package_manifest；
- stream cursors；
- key_set_version；
- maintenance_notice。

## 7.2 Push

POST /api/pos/v1/sync/push

~~~json
{
  "protocol_version": 1,
  "schema_version": 1,
  "request_id": "01K...",
  "device_id": "01K...",
  "store_id": "01K...",
  "batch_id": "01K...",
  "events": [
    {
      "event_id": "01K...",
      "device_sequence": 1088,
      "stream": "order.command",
      "event_type": "offline.order.completed.v1",
      "aggregate_id": "01K-order",
      "aggregate_version": 5,
      "occurred_at": "2026-08-15T10:01:02+08:00",
      "payload_hash": "sha256:...",
      "payload": {}
    }
  ]
}
~~~

响应：

~~~json
{
  "request_id": "01K...",
  "batch_id": "01K...",
  "server_time": "2026-08-15T02:01:04.125Z",
  "results": [
    {
      "event_id": "01K...",
      "status": "ACCEPTED",
      "server_reference": "01K...",
      "error_code": null,
      "retry_after_ms": null
    }
  ],
  "next_pull_hint": true
}
~~~

## 7.3 Push 结果

| status | 客户端动作 |
|---|---|
| ACCEPTED | 标记 ACKED，可按保留策略归档 |
| ACCEPTED_PENDING | 云端 Inbox 已持久化，业务处理中；不得重建新事件 |
| DUPLICATE | 视同成功，校验 server_reference |
| REJECTED_RETRYABLE | 保留事件，按 retry_after 重试 |
| REJECTED_FINAL | 停止自动重试，进入人工处置 |
| CONFLICT | 保存冲突详情，执行领域冲突策略 |
| DEVICE_BLOCKED | 全局转 BLOCKED |

HTTP 200 表示批次信封被处理，不代表每个事件业务成功；客户端必须逐项读取 results。

## 7.4 Pull

GET /api/pos/v1/sync/pull?stream={stream}&cursor={cursor}&limit={limit}

响应：

~~~json
{
  "stream": "config.critical",
  "cursor": "opaque-old",
  "next_cursor": "opaque-next",
  "has_more": false,
  "events": [
    {
      "event_id": "01K...",
      "event_type": "pos.config.changed.v1",
      "aggregate_id": "01K...",
      "aggregate_version": 9,
      "occurred_at": "2026-08-15T02:02:00Z",
      "payload_hash": "sha256:...",
      "payload": {}
    }
  ]
}
~~~

## 7.5 Ack

POST /api/pos/v1/sync/ack

用于确认需要显式消费证明的远程命令或包切换。普通 Pull 事件游标在本地事务成功后随下一次请求隐式确认，避免多余往返。

## 7.6 包下载

GET /api/pos/v1/sync/packages/{package_id}

支持：

- Range 分段下载；
- ETag/If-None-Match；
- Content-Length；
- SHA-256；
- gzip 或 zstd；
- 短期签名 URL 或设备鉴权；
- 断点续传。

---

# 八、批处理与重试

## 8.1 限制

商业 V1 建议：

- Push 每批最多 100 个事件；
- 未压缩正文最多 2 MiB；
- 单事件最多 256 KiB；
- Pull 默认 200 条，最大 1000 条；
- 事件处理超时前返回 ACCEPTED_PENDING；
- 大附件使用对象存储，不嵌入同步信封。

具体值通过 bootstrap 能力协商，客户端取服务端较小值。

## 8.2 退避

可重试错误使用带抖动指数退避：

- 初始 1 秒；
- 上限 5 分钟；
- 安全撤销和交易 Outbox 优先；
- 4xx 永久错误不盲目重试；
- 429/503 遵循 Retry-After；
- 网络恢复时避免所有终端同时洪峰上传。

## 8.3 SENDING 恢复

进程崩溃可能留下 SENDING：

- 启动时将超过 sending_timeout 的记录转 RETRY；
- 复用原 event_id、batch 事件内容和 payload_hash；
- 新 HTTP request_id 可以变化；
- 不复制业务事件；
- ACKED 记录永不重新执行，只可按策略重新核验。

---

# 九、数据包协议

## 9.1 包类型

| 包 | 内容 |
|---|---|
| MASTERDATA_FULL | 商品、SKU、条码、单位、门店范围 |
| PRICEBOOK_FULL | 门店和渠道价格 |
| PROMOTION_FULL | 规则 AST、策略和解释码 |
| CONFIG_FULL | 收银、班次、设备与风险配置 |
| TEMPLATE_FULL | 小票、标签和打印模板 |
| DELTA | 相对某基线版本的增量 |

## 9.2 Manifest

~~~json
{
  "package_id": "01K...",
  "package_type": "PROMOTION_FULL",
  "tenant_id": "000001",
  "scope": {"store_id": "01K..."},
  "version": "PR-20260815-08",
  "base_version": null,
  "schema_version": 1,
  "engine_compatibility": {
    "min": "1.0.0",
    "max_exclusive": "2.0.0"
  },
  "issued_at": "2026-08-15T02:00:00Z",
  "effective_from": "2026-08-15T03:00:00Z",
  "expires_at": "2026-08-22T03:00:00Z",
  "size_bytes": 183920,
  "sha256": "...",
  "signature_key_id": "pkg-key-2026-02",
  "signature": "base64..."
}
~~~

## 9.3 双槽安装

1. 下载到 staging 临时文件。
2. 校验长度和 SHA-256。
3. 校验平台签名、tenant_id、store scope、时间和兼容版本。
4. 解包到 inactive slot。
5. 在 inactive SQLite/文件结构运行 Schema 和业务校验。
6. 执行抽样查价、条码和促销黄金样例。
7. 在一个本地事务内切换 active_version 指针。
8. 保留上一有效槽用于回滚。
9. 上报 package.installed 或 package.failed。

严禁在 active 表中边下载边覆盖。

## 9.4 增量包

- 必须声明 base_version。
- 客户端基线不一致时拒绝应用并请求 FULL。
- 删除使用 tombstone，不依赖物理缺失。
- 同一对象版本只前进不倒退。
- 增量应用和游标推进必须在一个事务中。
- 增量链长度超过阈值时服务端提供新全量包。

---

# 十、下行事件应用

## 10.1 Inbox

本地 Inbox 唯一键为 event_id，保存：

- stream；
- payload_hash；
- aggregate_id/version；
- received_at；
- applied_at；
- result_code。

相同 event_id、相同 hash 返回已处理；相同 event_id、不同 hash 立即 BLOCKED 并上报安全事件。

## 10.2 原子应用

一批 Pull 事件可以逐事件事务处理，但只有连续成功应用到 next_cursor 才推进游标。某事件失败：

- 可重试：保留当前位置，稍后重试；
- 永久不兼容：停止该流并请求全量包或升级；
- 非关键展示事件：可进入隔离区并按策略继续；
- 安全和配置关键事件：失败时设备转 BLOCKED 或 DEGRADED。

## 10.3 聚合版本

- version = current + 1：应用。
- version 小于等于 current：若 hash/业务结果一致，视为重复。
- version 大于 current + 1：记录 VERSION_GAP，暂停该聚合并拉快照。
- 本地不存在且收到非初始版本：请求聚合快照。
- 不通过时间戳决定覆盖。

---

# 十一、上行事件处理

## 11.1 云端 Inbox 事务

Sync Gateway 收到每个事件：

1. 从设备会话解析 tenant_id、store_id、device_id。
2. 校验协议、Schema、签名、大小和字段。
3. 规范化负载并计算 payload_hash。
4. 尝试插入 sync_inbox 唯一事件。
5. 如已存在，比较 hash 并返回 DUPLICATE 或安全冲突。
6. 持久化成功后可返回 ACCEPTED_PENDING。
7. 领域消费者以 event_id/command_id 幂等执行。
8. 保存业务结果并供 POS 查询。

不得在未落 Inbox 的情况下先异步投递然后返回成功。

## 11.2 离线订单

服务端复核：

- 设备在 occurred_at 对应的离线授权窗口内；
- tenant、store、terminal、shift 匹配；
- order_id、line_id、payment_id、command_id 为规范 ULID；
- 金额为 BIGINT minor，数量为 DECIMAL 字符串；
- promotion package 和 fingerprint 有效；
- 现金支付与班次现金流水一致；
- 本地订单状态迁移合法；
- 库存命令、打印任务和 Outbox 引用完整。

通过后保留客户端聚合 ID，写云端订单、支付、库存命令和 Outbox。业务冲突不得通过创建新订单绕过。

## 11.3 ACCEPTED_PENDING 查询

GET /api/pos/v1/sync/results/{event_id}

结果状态：

- PENDING；
- APPLIED；
- DUPLICATE；
- CONFLICT；
- FINAL_REJECTED。

POS 对未完成结果按退避查询，也可由 Pull 流接收 sync.result.changed.v1。

---

# 十二、冲突处理矩阵

| 对象/冲突 | 权威来源 | 处理 |
|---|---|---|
| 相同事件 ID、不同 hash | 安全规则 | 阻断设备，禁止自动合并 |
| 离线订单 ID 已存在且内容相同 | 幂等记录 | DUPLICATE |
| 离线订单 ID 已存在且内容不同 | 云端已存事实 | CONFLICT，人工处置 |
| 商品已下架但离线已现金成交 | 交易事实 + 风险策略 | 接受历史成交，标记 MASTERDATA_STALE |
| 离线售价与云端当前价不同 | 成交签名快照 | 有效包则接受，不按当前价覆盖 |
| 促销包过期 | 规则授权 | 按宽限期接受或 FINAL_REJECTED/人工处置 |
| 云端库存不足 | 库存账本 | 幂等扣减并形成 OFFLINE_OVERSELL |
| 班次已被云端关闭 | 班次事实 | 接受交易到异常子班次或人工日结队列 |
| 会员券已在别处核销 | 权益账本 | 订单不静默改价，形成权益差异 |
| 草稿被另一设备修改 | 草稿 owner/version | 拒绝覆盖，允许复制为新草稿 |
| 设备时间漂移 | 服务端时间 | 保存 occurred_at，标记 CLOCK_SKEW |
| 配置版本落后 | 云端版本 | 下发新版本，不回写历史成交 |

## 12.1 禁止的自动合并

- 不把两张成交订单合成一张。
- 不改变已收现金金额使其匹配云端现价。
- 不把 UNKNOWN 电子支付改为 FAILED。
- 不直接把本地库存余额覆盖云端余额。
- 不根据最后修改时间覆盖订单、支付、库存或班次账本。

---

# 十三、离线能力矩阵

| 能力 | ONLINE | DEGRADED | OFFLINE |
|---|---:|---:|---:|
| 商品扫码与查价 | 是 | 是 | 包有效时是 |
| 现金销售 | 是 | 是 | 策略允许时是 |
| 电子支付新发起 | 是 | 视渠道可达 | 否 |
| 已发起电子支付查询 | 是 | 是 | 本地记录，恢复后查 |
| 挂单 | 是 | 是 | 是 |
| 零元单 | 是 | 是 | 规则包有效时是 |
| 优惠券 | 是 | 令牌/在线 | 仅签名离线令牌 |
| 储值/积分 | 是 | 在线或额度令牌 | 默认否 |
| 退款 | 是 | 受控 | 默认否 |
| 无原单退款 | 受审批 | 否 | 否 |
| 商品建档/改价 | 是 | 后台可用时 | 否 |
| 盘点 | 是 | 是 | 可本地采集，恢复后提交 |
| 交班 | 是 | 可本地 | 可本地关班，云端待确认 |

高价值、序列号、礼品卡、受监管和高欺诈风险商品可覆盖为 OFFLINE_FORBIDDEN。

---

# 十四、安全

## 14.1 传输与身份

- TLS 1.2+，优先 TLS 1.3。
- 设备使用短期令牌，并可选 mTLS。
- 请求关键摘要可使用设备私钥签名。
- 服务端验证 nonce、request_id、时间窗口和重放。
- 证书、令牌、包签名密钥支持轮换和吊销。
- 不把商户支付密钥、会员明文敏感数据下发 POS。

## 14.2 负载完整性

- payload_hash 使用规范 JSON 的 SHA-256。
- 规范化明确字段排序、UTF-8、数字字符串、空值和时间格式。
- 包 manifest 和内容分别校验。
- 相同 ID 不同 hash 视为篡改或程序严重缺陷。
- 本地审计链可选使用 previous_hash 构建哈希链。

## 14.3 本地数据

- Android Keystore 保存不可导出私钥。
- 数据库备份、日志和导出脱敏。
- 退出登录不删除未同步交易。
- 恢复出厂或解绑前必须检查 Outbox；有积压时需要主管授权和加密导出。
- Root、调试、签名不一致和应用完整性风险进入设备策略。
- 日志禁止记录完整支付码、银行卡信息、访问令牌和会员敏感字段。

## 14.4 克隆检测

以下情况触发：

- 同 device_id 同时从不合理地理/网络位置活跃；
- 公钥或硬件证明变化；
- device_sequence 大范围回退；
- 大量 event_id/hash 冲突；
- 已吊销证书继续请求。

处置可为 DEGRADED、BLOCKED、强制重新激活，并保留未同步交易的受控导出通道。

---

# 十五、Schema 与版本兼容

## 15.1 版本

| 版本 | 作用 |
|---|---|
| protocol_version | HTTP 流程和状态语义主版本 |
| schema_version | 信封结构版本 |
| event_version | 单事件 payload 版本 |
| app_version | POS 应用版本 |
| package schema_version | 数据包结构版本 |
| engine_version | 促销引擎语义版本 |

## 15.2 兼容规则

- 新增可选字段属于向后兼容。
- 字段删除、重命名、类型或语义变化必须升事件主版本。
- 客户端忽略未知可选字段，但不得忽略 unknown_must_understand 列表中的字段。
- 服务端在支持窗口内接受至少当前和上一 POS 主版本。
- 不兼容时返回 UPGRADE_REQUIRED，并给出最低版本与宽限期。
- 数据库迁移采用 Expand-Migrate-Contract，先部署兼容读写再收缩。

## 15.3 Schema Registry

事件和包 Schema：

- 使用 JSON Schema 2020-12 或选定统一版本；
- 在代码库版本管理；
- CI 执行兼容性检查；
- Java 与 Dart 生成模型或共享测试；
- 示例、错误码和事件目录自动发布；
- 不允许未登记事件进入生产 Outbox。

---

# 十六、错误码

| 错误码 | 级别 | 客户端动作 |
|---|---|---|
| AUTH_EXPIRED | 会话 | 刷新令牌 |
| DEVICE_REVOKED | 阻断 | 转 BLOCKED |
| DEVICE_SCOPE_MISMATCH | 阻断 | 停止上传并告警 |
| PROTOCOL_UNSUPPORTED | 升级 | 按最低版本升级 |
| SCHEMA_UNSUPPORTED | 升级 | 升级应用或拉兼容包 |
| PAYLOAD_HASH_MISMATCH | 安全 | BLOCKED，禁止自动重试 |
| EVENT_DUPLICATE | 成功 | 标记 ACKED |
| EVENT_VERSION_GAP | 可恢复 | 拉取快照/缺失事件 |
| CURSOR_EXPIRED | 可恢复 | 获取全量包 |
| PACKAGE_HASH_INVALID | 可重试 | 删除 staging 后重下 |
| PACKAGE_SIGNATURE_INVALID | 安全 | BLOCKED 并告警 |
| STORAGE_LOW | 本地 | 清理安全缓存，限制交易 |
| STORAGE_CORRUPTED | 本地 | 进入恢复模式 |
| CLOCK_SKEW_EXCEEDED | 风险 | 校时并限制时效能力 |
| DOMAIN_CONFLICT | 业务 | 展示处置任务 |
| RETRY_LATER | 可重试 | 遵循 retry_after |
| FINAL_REJECTED | 业务 | 停止自动重试，人工处理 |

---

# 十七、崩溃与灾难恢复

## 17.1 启动恢复

启动顺序：

1. 校验应用签名和设备状态。
2. 检查 SQLite quick_check、迁移状态和可用空间。
3. 恢复未完成本地事务。
4. 把超时 SENDING 事件转 RETRY。
5. 检查活动规则/主数据包签名与有效期。
6. 恢复未完成电子支付为 UNKNOWN。
7. 恢复打印队列。
8. 启动连接检测和安全 Pull。
9. 进入 ONLINE、OFFLINE 或 BLOCKED。

## 17.2 存储不足

阈值建议：

- 低于 500 MiB：告警并清理可再下载缓存；
- 低于 200 MiB：禁止下载大包，优先同步和归档；
- 低于 100 MiB：禁止新增交易，防止原子提交失败。

具体阈值按设备容量下发。不得自动删除未 ACK 的 Outbox、交易、现金流水或审计。

## 17.3 数据库损坏

- 停止交易。
- 保留损坏文件只读副本和哈希。
- 尝试 SQLite 恢复到新文件。
- 校验订单、支付、班次、Outbox 数量和哈希。
- 可从已 ACK 云端快照重建投影，但未同步交易必须优先取证恢复。
- 恢复结果需主管确认并上报安全事件。

## 17.4 卸载与换机

- 正常解绑必须 Outbox 为 0 或完成加密迁移包。
- 迁移包绑定目标设备一次性授权，包含交易和同步状态，不包含可导出私钥。
- 目标设备导入后重新签名并继续原 event_id。
- 原设备证书立即吊销。
- 禁止用普通文件复制 SQLite 克隆设备。

---

# 十八、可观测性

## 18.1 终端指标

- connectivity_state；
- last_successful_push_at、last_successful_pull_at；
- outbox_pending_count、outbox_oldest_age；
- inbox_failure_count；
- package_active_version、package_age；
- clock_skew_ms；
- sqlite_size_bytes、free_storage_bytes；
- unknown_payment_count；
- last_full_sync_at；
- app/protocol/schema version；
- crash_recovery_count。

## 18.2 云端指标

- sync_push_latency_ms、sync_pull_latency_ms；
- accepted、duplicate、retryable、final_rejected、conflict 数量；
- per_device_sequence_gap；
- inbox_processing_lag；
- package_download_success_rate；
- device_online_ratio；
- tenant/store 离线积压；
- payload_hash_mismatch_total；
- cursor_expired_total；
- stale_app_version_count。

## 18.3 告警

| 告警 | 级别 |
|---|---|
| event_id 相同但 hash 不同 | P0 |
| 设备证书疑似克隆 | P0 |
| 现金交易 Outbox 超过 30 分钟未上传 | P1/P2，按网络状态 |
| UNKNOWN 支付恢复后仍未查询 | P1 |
| 关键配置/撤销事件无法应用 | P1 |
| 门店 50% 以上设备同时离线 | P1 |
| 数据包签名失败 | P0 |
| 本地存储进入禁止交易阈值 | P1 |

---

# 十九、性能与 SLO

## 19.1 终端

- 本地现金成交事务 P95 小于 150 ms，不含打印。
- 本地扫码查询 P95 小于 50 ms。
- Outbox 写入与业务事务同提交。
- 网络恢复后 1 分钟内开始推送交易积压。
- 前台收银不得被大数据包安装长时间阻塞。

## 19.2 同步服务

- Push API P95 小于 300 ms；复杂领域处理可返回 ACCEPTED_PENDING。
- Pull API P95 小于 300 ms。
- 事件持久化可用性不低于商业 V1 服务 SLO。
- 单门店网络恢复时支持数十台设备带抖动上传。
- 关键安全流端到端下发 P95 小于 2 分钟。

## 19.3 保留期

- POS ACKED Outbox 保留建议 30 天或满足审计策略。
- 云端 Sync Inbox 不短于交易争议与重复重放窗口。
- 游标变更日志保留期必须大于最长允许离线期。
- 包保留当前、上一和审计要求版本。

---

# 二十、验收测试

## 20.1 协议一致性

- Java 与 Dart 使用同一 JSON Schema。
- canonical JSON 和 SHA-256 黄金向量一致。
- 未知可选字段兼容。
- must-understand 字段不支持时正确拒绝。
- 相同事件重复 100 次仅一次业务效果。
- 同 ID 不同 hash 必须阻断。
- 每个错误码都有客户端动作测试。

## 20.2 离线业务

1. 断网 8 小时持续现金销售。
2. POS 在本地事务提交前断电。
3. 提交后、打印前断电。
4. 提交后、上传响应前断网。
5. 两台终端同时离线销售同一 SKU。
6. 离线规则包在营业中到期。
7. 离线券在两台设备重复使用。
8. 本地班次关闭后云端已有另一关闭记录。
9. 网络恢复后积压乱序、重复上传。
10. 主数据已下架但离线订单已成交。

## 20.3 数据包

- 分段下载中断续传；
- 内容被修改；
- 签名密钥轮换；
- base_version 不匹配；
- 安装中断电；
- inactive slot 校验失败；
- 切换 active 指针后崩溃；
- 增量游标过期回全量；
- 低存储和包过大；
- 老客户端收到新可选字段。

## 20.4 安全

- 令牌过期和刷新；
- 设备封禁；
- 证书吊销；
- 重放旧请求；
- request_id 重复；
- device_sequence 回退；
- SQLite 文件被复制到另一设备；
- Root/调试风险；
- 日志敏感字段扫描；
- 跨 tenant_id、store_id 越权请求。

## 20.5 容量与耐久

- 10 万 Outbox 积压恢复；
- 2 GiB 本地数据库压力；
- 30 天离线边界；
- 低端 Android 设备持续 12 小时收银；
- 进程被系统反复杀死；
- 网络在 2G、Wi-Fi 抖动、DNS 故障间切换；
- 服务端限流与 Retry-After；
- 门店集中恢复的惊群测试。

---

# 二十一、实施与运维手册要点

## 21.1 上线前

- 设备激活、时间、时区、存储和网络检查；
- 初始主数据、价格、促销、配置和模板包校验；
- 现金、打印、断网与恢复演练；
- 门店离线额度、最长离线时长和禁售清单确认；
- 运维联系人、告警和异常处置权限配置。

## 21.2 日常

- 每日检查 Outbox 积压、UNKNOWN 支付和包版本；
- 每周抽查离线成交指纹和库存差异；
- 定期轮换设备证书和签名密钥；
- 分批升级 POS，保持服务端兼容窗口；
- 不通过人工改 SQLite 修复业务。

## 21.3 门店异常处置

- 网络不可用：确认 OFFLINE 标识与离线授权剩余时间。
- 支付未知：停止再次扣款，执行原单查询。
- 存储不足：优先同步，按白名单清理缓存。
- 设备损坏：保护设备、走加密迁移或恢复流程。
- 数据冲突：保留小票和现金证据，提交运营工作台。
- 设备 BLOCKED：不得绕过，联系授权人员重新激活。

---

# 二十二、研发交付清单

## 22.1 Flutter

- SQLite Schema、WAL 与事务协调器；
- Outbox/Inbox、游标、重试和批处理；
- Connectivity 状态机；
- 双槽包管理与签名验证；
- 本地 ULID、canonical JSON 和 payload hash；
- 安全存储、设备激活与证书续期；
- 崩溃恢复、存储保护和同步诊断页面；
- 与 Java 共享协议契约测试。

## 22.2 服务端

- Device Service、Sync Gateway、Sync Inbox；
- Push/Pull/Ack/Bootstrap/Result/Package API；
- 领域命令路由、Outbox 和 Change Feed；
- 包构建、签名、版本和 CDN；
- 协议/Schema 注册、兼容性门禁；
- 积压、冲突、封禁与修复工作台；
- 限流、审计、指标和告警。

## 22.3 QA

- 网络故障代理；
- 断电/杀进程自动化；
- 多设备并发和大量积压工具；
- JSON Schema、哈希、签名黄金向量；
- 数据包破坏与版本兼容矩阵；
- 订单、现金、库存和班次跨库对账。

---

# 二十三、商业 V1 决策摘要

1. Android POS 使用 Flutter + SQLite WAL，交易与 Outbox 原子提交。
2. 协议是至少一次传输，通过 ULID、payload_hash、Inbox/Outbox 实现恰好一次业务效果。
3. 云端保留终端生成的订单和事件 ID，不重新编号。
4. 离线默认只允许现金销售、挂单和受控零元单，电子支付禁止离线新发起。
5. 主数据、价格、促销和配置通过签名数据包双槽原子切换。
6. 每个同步流使用独立不透明游标，不依赖客户端时间排序。
7. 冲突按领域规则处理，禁止 Last-Write-Wins。
8. 相同事件 ID 但 payload_hash 不同属于安全事件，设备转 BLOCKED。
9. 支付 UNKNOWN、未 ACK 交易和现金流水不得因卸载、退出或清理缓存丢失。
10. 服务端至少兼容当前和上一 POS 主版本，Schema 变更进入 CI 兼容门禁。

本规范批准后，信封、状态、错误码、幂等行为、游标、包格式和离线能力矩阵成为商业 V1 冻结协议；任何破坏性改变必须提升版本、提供迁移路径并完成多版本互操作验收。
