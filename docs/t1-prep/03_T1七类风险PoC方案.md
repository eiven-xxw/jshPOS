# T1 七类风险 PoC 方案

> 文档编号：JSH-POS-T1P-003
> 版本：1.0
> 状态：启动评审候选
> 依据：技术规范 16.1、详细设计 31/32/35/36/39、ADR-006—010、ADR-017

## 1. 方案总则

T1 的 PoC 是可丢弃技术探针，不是业务模块雏形。所有探针使用 `poc`/`synthetic` 命名空间和合成对象，不得定义正式 `Order`、`Payment`、`Inventory`、`Promotion` 聚合，不得创建可被 T2 默认为正式模型的数据库表。

每个 PoC 必须具备五个输出：假设、最小实现、故障注入、量化结果、适用边界。结果只能是：

- `PASS`：相应证据等级的全部硬门槛通过；
- `FAIL`：可执行门槛失败，已记录最小复现和影响；
- `BLOCKED`：输入缺失，未执行；
- `INCONCLUSIVE`：证据不足，不得按 PASS 处理，退出评审按 FAIL 或延期决策。

## 2. 共用测试架构

```text
Synthetic Scenario Driver
  ├─ Flutter Probe App
  │   ├─ Device Adapter contract ─ Kotlin Fake/Vendor Plugin
  │   ├─ SQLite probe ─ Transaction + Outbox
  │   ├─ Package Stager ─ Verify + Atomic Switch
  │   └─ Upgrade Harness
  ├─ RuoYi Probe Module
  │   ├─ Synthetic Inbox/Idempotency endpoint
  │   ├─ Tenant isolation fixtures
  │   └─ Payment provider test contract
  ├─ Fault Controller
  │   ├─ network drop/delay/duplicate/reorder
  │   ├─ process kill/restart/storage fault
  │   ├─ callback replay/out-of-order
  │   └─ migration/package corruption
  └─ Evidence Collector
      ├─ JUnit/Flutter test reports
      ├─ structured redacted logs
      ├─ device/sandbox metadata
      └─ SHA-256 manifest
```

服务端探针必须与正式模块隔离并可整体删除；Flutter 探针不得进入生产路由。任何探针若需要绕开 T0 安全门禁，直接判定 `NO-GO`。

## 3. PoC-1：Android 设备与外设适配

### 3.1 需求与假设

需求：`T1-HWD-001/002`、`T1-PRN-001`、`T1-SCN-001`、`T1-SCL-001`、`T1-IO-001`。

待证伪假设：Flutter 可只依赖稳定 Dart 能力契约，Kotlin 插件能封装主流厂商 SDK/AIDL/USB/LAN 差异；更换厂商不需要改 UI 和业务层。

### 3.2 稳定能力面

| 能力 | 请求/事件最小字段 | 强制返回语义 |
|---|---|---|
| capability | 能力名、协议版本 | supported、limits、vendor/firmware、degraded reason |
| print | requestId、模板版本、字节/图像摘要、份数 | accepted/completed/unknown/failed、vendorCode、retryable |
| scan | sessionId、码制白名单、节流 | value、symbology、source、occurredAt、sequence |
| scale | sessionId、单位、稳定窗口 | gross/tare/net、decimal scale、stable、overload、sequence |
| drawer | requestId、脉冲配置 | accepted/unknown/failed；重复请求不重复开箱 |
| display | requestId、布局版本、脱敏内容 | applied/unsupported/failed、displayId |
| lifecycle | init/health/recover/dispose | state、latency、lastError、recoveryAction |

错误统一分为 `UNSUPPORTED`、`NOT_READY`、`TIMEOUT`、`DISCONNECTED`、`BUSY`、`INVALID_INPUT`、`PERMISSION_DENIED`、`VENDOR_ERROR`、`UNKNOWN_RESULT`。必须保留厂商原错误码但不得将其泄漏为上层控制流。

### 3.3 两种打印路径

1. 路径 A：主认证一体机内置打印机，通过厂商 SDK/AIDL；
2. 路径 B：标准外置打印机，优先 ESC/POS over LAN；如最终门店网络条件不成立，可评审替换为 USB，但两条路径必须保持不同故障域。

钱箱优先随打印机脉冲接口；客显优先验证一体机副屏，其次标准外置显示。真实型号未选前以上均为 `ASSUMPTION`。

### 3.4 最小探针

- Dart contract tests：每项能力成功、unsupported、timeout、disconnect、duplicate、recover；
- Kotlin Fake：可脚本化延迟、断连、半成功、未知结果、重复事件和乱序事件；
- Vendor Plugin：只在主认证型号和取得授权 SDK 后增加；
- Probe App：固定测试模板和合成条码/重量，不含正式收银页面；
- 设备诊断：输出脱敏的型号、Android、固件、SDK/插件版本、能力矩阵和最近错误。

### 3.5 故障与判定

- 打印提交前/后断连、缺纸、开盖、服务进程重启、重复 requestId；
- 扫码连扫、同码抖动、未知码制、事件乱序、扫码头热插拔；
- 秤不稳定、负数、超量程、断连、单位/小数位不兼容；
- 钱箱命令超时和重复；客显不可用、切换前后台、内容过长；
- 插件初始化失败、Activity 重建、Flutter Engine 重建、Android 进程重启。

通过门槛详见文档 07。任何 Fake PASS 只证明契约；实机项必须带设备指纹和 `REAL_DEVICE` 证据。

### 3.6 当前阻断

主认证厂商/型号、Android/固件、CPU/内存、SDK、实机及外设均未落实，因此真实设备部分为 `BLOCKED`。T1 启动确认后只能先做 `T1-HWD-001` Fake 契约。

## 4. PoC-2：支付不确定性与适配契约

### 4.1 需求与假设

需求：`T1-PAY-001/002`。

待证伪假设：五家候选聚合支付的公开能力可映射到统一的“命令 + 查询 + 回调 + 对账”语义；客户端超时不会触发第二次扣款，所有 `UNKNOWN` 最终仅通过原交易查询、回调或对账收敛。

### 4.2 统一测试契约

| 对象 | 强制标识/字段 | 不变量 |
|---|---|---|
| provider profile | providerCode、apiVersion、capabilities、signature、callback、limits | 明确不支持项，不以空值假装支持 |
| payment intent | syntheticIntentId、merchantRequestId、amountMinor、currency、expiresAt | 同幂等键和参数只产生一个合作方交易 |
| command result | providerTradeId、state、errorClass、rawCodeHash | timeout/解析失败不得映射 FAILED |
| query result | originalRequestId/providerTradeId、state、providerTime | 只能收敛，不逆转已终态事实 |
| callback envelope | eventId、tradeId、state、occurredAt、signatureMeta | 先验签、再幂等、允许乱序 |
| refund probe | refundRequestId、originalTradeId、amountMinor | 退款自身幂等，UNKNOWN 同样查询收敛 |
| reconciliation row | providerTradeId、amount/state/date | 差异不被覆盖，形成可解释记录 |

测试状态限定为 `CREATED`、`PROCESSING`、`SUCCEEDED`、`FAILED`、`UNKNOWN`、`CLOSED`；退款使用独立状态，不与支付状态混写。PoC 不创建正式支付领域表。

### 4.3 五家档案与一个真实沙箱

- 易宝支付、汇付天下、拉卡拉、富友、收钱吧：建立 Provider Profile、字段映射、签名/回调/查询/退款差异和缺口；
- 五个可脚本化 Fake：共用一套 provider contract test；
- 仅选择一家资料完整、授权明确的真实沙箱执行端到端；
- 沙箱选择不等于商业首发合作方确定，不得把公开文档可访问写成“已接入”。

### 4.4 故障场景

- 请求未发出、已发出但客户端超时、合作方返回处理中/未知、响应损坏；
- 查询在成功前/后延迟，回调先于响应、重复、乱序、签名错误、旧时间戳重放；
- 同幂等键同参数、同幂等键不同参数、不同键同业务引用；
- 支付成功但本地打印失败（只验证支付事实不回退，不实现订单）；
- 退款响应超时、重复退款、部分退款超额、原交易不存在；
- 对账有平台单边、合作方单边、金额/状态差异。

### 4.5 安全边界与阻断

禁止真实金额、生产商户号、生产终端、生产私钥和银行卡数据。真实沙箱账号/终端/文档/联系人未提供，`T1-PAY-002` 为 `BLOCKED`；五家 Fake 契约可在项目发起人确认后执行。

## 5. PoC-3：本地崩溃、进程重启与断电恢复

### 5.1 需求与假设

需求：`T1-OFF-001`。

待证伪假设：合成本地事实、支付意图占位和 Outbox 可在同一 SQLite 事务原子提交；任一指令边界杀进程或模拟断电后，恢复结果只能是“全有”或“全无”，不会出现事实已提交但 Outbox 丢失。

### 5.2 探针模型

- `synthetic_fact`：ULID、tenant、terminal、payloadHash、createdAt；
- `synthetic_intent`：与 fact 一对一的测试意图，不含正式支付字段；
- `synthetic_outbox`：eventId、aggregateId、payloadHash、attempt、nextAttempt、state；
- `probe_checkpoint`：仅记录故障注入点和测试运行 ID，不参与事实判定。

三类核心记录必须在同一事务；WAL、synchronous、busy timeout、磁盘空间和数据库加密/密钥策略需记录固定参数。

### 5.3 注入点

1. 开始事务前；
2. 写 fact 后；
3. 写 intent 后；
4. 写 outbox 后、commit 前；
5. commit 返回前/后；
6. 出队标记前/后；
7. 上传已到服务端但本地未确认；
8. SQLite migration 每一步前/后；
9. 磁盘满、只读、I/O 错误、数据库文件损坏副本。

真实断电测试只能在可恢复测试机上进行；自动化进程 kill 不能冒充物理断电证据。

### 5.4 恢复断言

- 对每个 syntheticId，fact/intent/outbox 计数只能为 0/0/0 或 1/1/1；
- commit 成功事实重启后必须存在且 payloadHash 不变；
- 未提交事实不得“恢复”出来；
- outbox 可重复发送但服务端业务效果最多一次；
- 损坏不可恢复时进入隔离/诊断状态，不允许重建空库掩盖事实；
- 恢复日志不得包含合成敏感字段原文。

## 6. PoC-4：同步幂等、重复、乱序与游标恢复

### 6.1 需求与假设

需求：`T1-SYN-001`。

待证伪假设：至少一次投递下，Outbox/Inbox + 幂等键能确保合成业务效果一次；上/下行发生重复、延迟、乱序、丢包或游标中断时不会静默丢事件或回退已确认版本。

### 6.2 上行探针

- eventId 全局唯一；tenant/terminal 来自可信设备会话，不信任 payload 自报；
- 服务端 Inbox 先登记幂等，再与合成效果同事务提交；
- 同 eventId 同 payload 返回同结果；同 eventId 不同 payload 返回不可重试冲突并审计；
- ACK 丢失只触发同事件重投，不生成新事件；
- 重试使用有上限指数退避和抖动，死信保留原摘要和人工恢复入口。

### 6.3 下行包探针

- manifest：tenant、dataset、from/to version、schemaVersion、recordCount、payloadHash、signature、generatedAt；
- 下载到 staging，完整验签/计数/Schema/兼容检查后以单一指针事务切换；
- 崩溃时 active 仍指向旧完整版本或新完整版本，不指向半包；
- 旧游标不能覆盖新版本；增量缺口触发全量重建，而非猜测补齐；
- 包不包含跨租户记录，验签前不得导入活动数据。

### 6.4 网络故障模型

使用可复现种子控制 0—100% 丢包、10ms—30s 延迟、1—10 次重复、随机乱序、连接重置、HTTP 5xx/429、响应截断和客户端时钟偏差。所有运行保存 seed，失败可以一键复现。

## 7. PoC-5：多租户隔离旁路

### 7.1 需求与假设

需求：`T1-TEN-001`。

待证伪假设：租户隔离不仅依赖常规 ORM 拦截器，在 XML Mapper、原生 SQL、后台任务、缓存、导出、对象存储、日志和错误信息等旁路中同样成立。

### 7.2 测试租户与攻击者

- `tenant_alpha` / `tenant_beta`：完全虚构，存在相同业务键、相同条码和相邻 ULID；
- alpha 管理员、alpha 收银员、beta 管理员、平台受控运维、无租户服务账号；
- 攻击方式：改 URL/Body/Header 租户 ID、猜 ID、批量 ID、缓存键碰撞、导出任务串租户、异步上下文丢失、SQL 注入式过滤绕过、对象路径替换。

### 7.3 覆盖面

| 入口 | 强制断言 |
|---|---|
| Controller/API | 客户端 tenantId 不成为授权依据；越权 403/404 且审计 |
| Service/Mapper | XML/Wrapper/原生 SQL 均显式租户约束或可信隔离机制 |
| 后台 Job | 每次任务显式绑定租户范围；失败不落入默认租户 |
| Cache/Lock/Rate limit | key 含可信 tenant scope，跨租户不命中/不互锁 |
| Export/Report | 查询、暂存、下载 Token 和对象路径全链路隔离 |
| Object storage | 前缀与签名由服务端生成，不能替换租户路径 |
| Search/Logs/Metrics | 不因标签或错误消息泄露另一个租户内容 |
| Platform admin | 必须显式授权、理由、审计和最小时间窗 |

任一跨租户数据读取、修改、存在性泄漏或缓存污染均为 P0，立即停止相关 PoC。

## 8. PoC-6：商品/价格数据包完整性与原子切换

### 8.1 需求与假设

需求：`T1-DPK-001`。

待证伪假设：1 万/10 万 SKU 的合成数据包可在弱网断点续传、验签、校验后原子切换；损坏、篡改、跨租户、版本缺口或不兼容 Schema 不会污染活动数据。

### 8.2 数据集与版本

- convenience-10k：标准条码、包装单位、正常价格；
- snack-100k：高 SKU、整箱/散件映射、频繁版本；
- community-mixed：称重码/普通码混合样本，但不实现正式称重商品规则；
- 版本：full `v100`，incremental `v101..v120`，包含正常、缺口、重复和过期包；
- 每个记录含 syntheticSkuId、barcode、unitCode、priceMinor、version、payloadHash，不使用真实商户资料。

### 8.3 校验顺序

先校验 manifest/schemaVersion/tenant/version range，再验签和 payloadHash，再验证 recordCount/字段/唯一性/引用，最后导入 staging 并执行活动指针切换。任一失败保留旧版本，输出脱敏诊断，不做部分导入。

### 8.4 故障场景

分片丢失/重复/乱序、断点偏移错误、hash/签名篡改、压缩炸弹限制、磁盘空间不足、跨租户记录、重复条码、版本缺口、旧包重放、未知 schema、导入中杀进程、切换中杀进程、旧版本回收失败。

## 9. PoC-7：APK、SQLite 迁移、灰度与回滚

### 9.1 需求与假设

需求：`T1-UPG-001`。

待证伪假设：新 APK 可在主机允许的安装方式下灰度；本地数据库升级中断可安全恢复；新旧应用具有批准的兼容窗口；应用回退不会用旧 Schema 破坏新事实。

### 9.2 版本矩阵

| 应用 | DB | 服务端契约 | 预期 |
|---|---|---|---|
| A | N | V | 正常基线 |
| B | N（迁移前） | V/V+1 | 启动迁移或安全拒绝 |
| B | N+1 | V/V+1 | 正常 |
| A | N+1 | V | 只在声明兼容时运行，否则安全阻断并保留数据 |
| B 迁移失败 | N 或可识别中间态 | V | 恢复/前向修复，不静默清库 |

探针至少包含 additive migration、回填、索引创建、故意失败和不可兼容变更检测；不使用正式业务 Schema。

### 9.3 升级机制

- APK 及 manifest 必须签名/校验摘要；发布通道包含 ring、min/max compatible version、数据库版本和回退说明；
- 先 1 台实验机，再主认证机小环，禁止 T1 自动全量；
- 安装前检查电量、空间、活动任务和本地待同步探针；
- 迁移前生成受控备份/校验点，但恢复策略不得覆盖已成功产生的新事实；
- 厂商静默安装/自启动能力未确认前标记 `ASSUMPTION`，不能写入承诺。

### 9.4 故障场景

下载截断、摘要不符、签名不符、空间不足、安装被拒、升级中杀进程、迁移每步失败、旧应用启动新 DB、服务端先升/客户端先升、回退包损坏、设备离线跨多个版本升级。

## 10. PoC 输出模板

每一类在 `artifacts/t1/<run-id>/<wp>/` 生成：

- `run-metadata.json`：commit、build、环境、seed、证据等级、设备/沙箱脱敏指纹；
- `hypothesis.md`：假设、范围、非目标、版本和负责人；
- `results.json`：用例、门槛、实测值、PASS/FAIL/BLOCKED；
- `test-reports/`：JUnit/Flutter/契约报告；
- `logs/`：结构化、脱敏、限定大小；
- `fault-timeline.jsonl`：注入时刻和恢复事件；
- `sha256sums.txt`：证据摘要；
- `review.md`：实现者与独立复核者结论。

目录名 `artifacts` 表示 CI 制品逻辑结构，T1-Prep 不在仓库提交运行制品。

## 11. 跨 PoC 不变量

- 任何 Fake/沙箱/实机结果都携带正确证据等级；
- 任一重试不得生成第二次支付扣款命令或第二个合成业务效果；
- 客户端提供的 tenantId 不作为授权事实；
- 本地 commit 后事实和 Outbox 不可分离；
- 活动数据永不指向半包；
- 升级失败不清空本地事实，不用回滚覆盖已产生的新事实；
- 所有失败可用 runId、seed、版本和摘要复现；
- 任何生产密钥、真实支付和未脱敏数据出现均使整次运行无效并触发安全响应。
