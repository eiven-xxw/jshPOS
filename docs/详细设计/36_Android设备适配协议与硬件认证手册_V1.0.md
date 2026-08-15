# 连接器型商业收银经营平台

## Android 设备适配协议与硬件认证手册 V1.0

> 文档编号：POS-DD-036  
> 文档状态：架构评审稿  
> 技术基线：Flutter Android POS + Kotlin 原生适配层 + 可选 POS Edge Agent  
> 适用范围：一体收银机、平板、手持 POS、自助终端及其打印、扫码、称重、钱箱和客显外设  
> 基线日期：2026-08-15

---

# 文档说明

## 1. 编写目的

本文定义 Android POS 与硬件设备之间的分层协议、能力模型、驱动适配、故障语义、日志指标、版本兼容、测试矩阵和商业认证流程。目标是让 Flutter 业务代码不依赖某个收银机厂商 SDK，同时建立可持续扩展的自有硬件认证体系。

## 2. 核心结论

- Flutter 负责界面和业务流程，Kotlin 原生层负责 Android 权限、USB/Bluetooth/厂商 SDK。
- 所有硬件通过统一 Device Gateway 和版本化能力契约调用。
- 厂商 SDK 放在独立 Adapter，不得进入订单、支付或页面代码。
- 支付 PIN Pad、银行卡读卡器等敏感设备只调用持证支付机构接口，本系统不接触完整卡数据或 PIN。
- Android 为商业 V1 主终端；Windows 或特殊驱动由 Edge Agent 作为补充。
- “能打印一次”不等于商用兼容，必须通过稳定性、断电、重连、长稳和升级回归认证。

## 3. 官方基线

- Android USB Host 需要设备硬件支持，应用应声明 android.hardware.usb.host 并按 VID/PID 或接口筛选设备：[Android USB Host](https://developer.android.com/develop/connectivity/usb/host)。
- Android 12 及以上使用 BLUETOOTH_SCAN、BLUETOOTH_CONNECT 等细粒度权限：[Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)。
- 专用收银终端可由 DPC/EMM allowlist 后使用 lock task mode，而不是把普通屏幕固定当作安全 kiosk：[Lock task mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode)。
- POS 离线数据层以本地持久化数据源为应用读取来源，与 POS-DD-035 的 SQLite/Outbox 保持一致：[Android offline-first](https://developer.android.com/topic/architecture/data-layer/offline-first)。

---

# 一、适配范围

## 1.1 设备类别

| 类别 | 典型连接 | 商业 V1 |
|---|---|---:|
| 内置热敏打印机 | 厂商 AIDL/JAR/系统服务 | 必须 |
| USB 热敏打印机 | USB Host、USB-Serial | 必须 |
| 网口打印机 | TCP 9100、厂商协议 | 必须 |
| 蓝牙打印机 | Bluetooth Classic/BLE | 可以 |
| 内置/USB 扫码器 | HID、串口、厂商广播 | 必须 |
| 摄像头扫码 | CameraX/Flutter 插件 | 必须 |
| 电子秤 | 串口、USB-Serial、网口、厂商 SDK | 重点行业 |
| 钱箱 | 打印机脉冲、GPIO、厂商 SDK | 必须 |
| 客显/副屏 | Android Display、串口、Web/厂商 SDK | 应当 |
| 标签打印机 | USB、网口、蓝牙 | 零售扩展 |
| NFC/读卡器 | Android NFC、厂商 SDK | 会员扩展 |
| 支付 PIN Pad | 支付机构 SDK/专用终端 | 仅合规集成 |
| 厨打/云打印 | LAN、云连接器 | 餐饮扩展 |

## 1.2 不在适配层处理的业务

- 订单应收与支付状态；
- 促销计算；
- 库存账本；
- 会员身份业务；
- 渠道结算；
- 银行卡敏感认证数据。

适配层只把“设备能力与设备结果”转换为标准契约，业务含义由上层领域判断。

---

# 二、总体架构

## 2.1 分层

~~~text
Flutter UI / Application
        |
Device Gateway Dart API
        |
Pigeon/MethodChannel Typed Bridge
        |
Android Device Runtime (Kotlin)
        |
Capability Router + Adapter Registry
        |
Standard Adapter | Vendor Adapter | Edge Agent Proxy
        |
Printer / Scanner / Scale / Drawer / Customer Display
~~~

## 2.2 模块

| 模块 | 职责 |
|---|---|
| pos-device-contract | Dart/Kotlin 共享 DTO、枚举和错误码 |
| pos-device-gateway | Flutter 统一调用入口、超时和取消 |
| android-device-runtime | 权限、生命周期、线程、服务绑定和设备发现 |
| adapter-escpos | 标准 ESC/POS 打印与钱箱 |
| adapter-usb-serial | USB 串口基础传输 |
| adapter-hid-scanner | HID 扫码 |
| adapter-vendor-{name} | 厂商 AIDL/JAR/广播 SDK |
| adapter-edge-agent | 本地 HTTPS/WebSocket 代理 |
| device-diagnostics | 自检、日志、指标和认证工具 |

## 2.3 禁止依赖

- Flutter 页面不得 import 厂商插件。
- 订单模块不得调用 USB、串口或 AIDL。
- Adapter 不得直接写订单、支付、库存或班次数据库。
- Adapter 不得自行弹出不可控业务 UI。
- 设备线程不得阻塞 Flutter UI isolate 或 Android 主线程。
- 厂商 SDK 的 Model/Exception 不得穿透标准契约。

---

# 三、能力模型

## 3.1 DeviceDescriptor

~~~json
{
  "device_id": "local:printer:usb:vid-pid-serial",
  "device_type": "RECEIPT_PRINTER",
  "transport": "USB",
  "vendor": "VendorA",
  "model": "P80",
  "hardware_revision": "R2",
  "firmware_version": "1.8.3",
  "android_device_model": "XPOS-A8",
  "adapter_id": "adapter-vendor-a",
  "adapter_version": "1.2.0",
  "connection_state": "READY",
  "capabilities": {},
  "health": {}
}
~~~

## 3.2 能力声明

每个能力使用独立版本：

- receipt_print.v1；
- label_print.v1；
- barcode_scan.v1；
- weight_read.v1；
- cash_drawer.v1；
- customer_display.v1；
- nfc_read.v1；
- payment_terminal_proxy.v1。

能力字段：

| 字段 | 说明 |
|---|---|
| capability | 稳定能力代码 |
| contract_version | 契约版本 |
| supported | 是否可用 |
| limits | 纸宽、最大字符、称重精度等 |
| features | 切刀、二维码、蜂鸣、状态回读 |
| permissions | 所需 Android 权限 |
| quirks | 已认证的兼容性特征代码 |

业务代码先读取能力，不以厂商、型号或 Android Build 字符串猜测功能。

## 3.3 连接状态

| 状态 | 含义 |
|---|---|
| DISCOVERED | 已发现，尚未授权/打开 |
| PERMISSION_REQUIRED | 等待系统或用户权限 |
| CONNECTING | 正在连接或绑定服务 |
| READY | 可接受命令 |
| BUSY | 正执行互斥任务 |
| DEGRADED | 部分能力不可用 |
| DISCONNECTED | 已断开，可重连 |
| ERROR | 需要干预 |
| UNSUPPORTED | 硬件/固件/协议不兼容 |

状态改变发布 device.state_changed.v1，不改变交易状态。

---

# 四、统一调用协议

## 4.1 命令信封

设备命令在本地也必须携带授权上下文：tenant_id 使用 VARCHAR(20)，store_id、terminal_id、command_id 使用 ULID；Adapter 只能读取上下文用于审计和路由，不得自行切换租户或门店。

~~~json
{
  "command_id": "01K...",
  "capability": "receipt_print.v1",
  "target_device_id": "local:printer:1",
  "timeout_ms": 10000,
  "priority": "TRANSACTION",
  "idempotency_key": "print-job-01K...",
  "trace_id": "a8...",
  "payload": {}
}
~~~

## 4.2 结果信封

~~~json
{
  "command_id": "01K...",
  "status": "SUCCEEDED",
  "device_id": "local:printer:1",
  "started_at": "2026-08-15T10:00:00.000+08:00",
  "completed_at": "2026-08-15T10:00:00.280+08:00",
  "result": {},
  "error": null,
  "diagnostics_ref": "diag:01K..."
}
~~~

## 4.3 调用状态

| 状态 | 含义 |
|---|---|
| ACCEPTED | 已进入设备队列 |
| RUNNING | 正在执行 |
| SUCCEEDED | 设备协议确认完成 |
| FAILED | 明确失败 |
| UNKNOWN | 结果无法确认 |
| CANCELLED | 执行前取消 |
| TIMED_OUT | 调用超时；是否执行成功取决于能力语义 |

打印命令超时不等于订单失败；钱箱命令失败不等于现金支付失败。硬件结果与业务结果必须分离。

## 4.4 并发与队列

- 每台打印机默认单串行队列。
- 扫码器是持续事件源，不占用命令队列。
- 电子秤可持续订阅或单次读取，但串口访问互斥。
- 钱箱脉冲和打印共享端口时由打印 Adapter 排队。
- 高优先级交易小票先于测试页，不能插入正在输出的任务。
- 命令状态和幂等结果持久化，进程重启后可恢复。

---

# 五、打印协议

## 5.1 PrintDocument

上层提交语义化文档，不提交任意厂商字节：

- TextBlock；
- KeyValueRow；
- Table；
- Barcode；
- QrCode；
- Image；
- Divider；
- Feed；
- Cut；
- DrawerPulse。

每个元素带对齐、字号、粗体和 fallback。Adapter 根据纸宽、字符集和能力渲染。

## 5.2 PrintJob

| 字段 | 说明 |
|---|---|
| print_job_id | ULID，持久化幂等键 |
| document_type | SALE_RECEIPT、REFUND_RECEIPT、SHIFT_REPORT 等 |
| business_reference | 订单/退款/班次 ID |
| template_version | 小票模板版本 |
| copies | 份数 |
| target_policy | 指定设备或角色路由 |
| content_hash | 渲染前语义内容哈希 |
| retry_policy | 重试次数与人工确认 |

## 5.3 打印状态

PENDING → RENDERED → SENDING → PRINTED / FAILED / UNKNOWN。

- PRINTED 表示 Adapter 得到足够确认；低端打印机无状态回读时只能标记 SENT_UNCONFIRMED。
- 相同 print_job_id 不自动重复物理打印，除非用户执行 Reprint 并生成 reprint_reason。
- 补打必须在小票注明“补打”、次数、操作者和时间。
- 订单成功不依赖打印成功。

## 5.4 ESC/POS

- 以已认证命令子集为基线，不假设所有 ESC/POS 兼容机实现相同。
- 对中文使用明确代码页或位图渲染策略。
- 图片控制宽度、抖动和内存。
- 二维码优先原生命令，失败可降级位图。
- 切刀、蜂鸣、钱箱脉冲必须由 capability 确认。
- 网络打印设置连接、写入和总超时，禁止无限阻塞。

## 5.5 纸张与版式

支持 58 mm、80 mm 配置；认证记录实际可打印点宽。模板用逻辑列与语义布局，不硬编码字符数。必须测试：

- 中文、英文、数字混排；
- 长商品名与长金额；
- 二维码；
- 断纸、开盖、卡纸；
- 连续 1000 单；
- 低电压或网络抖动；
- 应用升级后模板回归。

---

# 六、扫码器协议

## 6.1 输入来源

- HID 键盘；
- Android KeyEvent；
- 厂商广播 Intent；
- AIDL/SDK 回调；
- USB-Serial；
- 摄像头识别。

统一输出 barcode.scanned.v1：

~~~json
{
  "scan_event_id": "01K...",
  "device_id": "local:scanner:1",
  "symbology": "EAN_13",
  "raw_value": "6901234567892",
  "normalized_value": "6901234567892",
  "occurred_at": "2026-08-15T10:00:00.100+08:00",
  "quality": null
}
~~~

## 6.2 去抖与边界

- HID 通过前后缀、字符间隔和终止键识别，不把人工键盘输入误判为扫码。
- 相同设备在 debounce_ms 内相同条码只发一次，连续销售模式可配置。
- 原始值与规范化值都保留，规范化不删除具有业务意义的前导零。
- 最大长度、字符集和 GS1 解析有上限，防止输入攻击。
- 摄像头仅在扫码页面启用，权限拒绝时可降级外置设备。

## 6.3 称重条码

称重条码解析由商品/条码领域完成，扫码 Adapter 只输出原始码和制式。价格或重量位、校验位和门店编码规则必须版本化，不写死在驱动。

---

# 七、电子秤协议

## 7.1 WeightReading

| 字段 | 类型 | 说明 |
|---|---|---|
| reading_id | ULID | 一次读数 |
| gross_weight | DECIMAL 字符串 | 毛重 |
| tare_weight | DECIMAL 字符串 | 皮重 |
| net_weight | DECIMAL 字符串 | 净重 |
| unit | 枚举 | kg、g 等 |
| stable | boolean | 稳定标识 |
| zero | boolean | 零点 |
| overload | boolean | 超载 |
| device_timestamp | 可空 | 设备时间 |
| received_at | 时间 | POS 接收时间 |

## 7.2 取重规则

- 交易取重必须 stable = true。
- net_weight 大于 0 且不超过设备量程和商品限制。
- 单位换算使用 Decimal。
- 稳定保持时间和连续一致读数次数可配置。
- 读数过期后不得用于结算。
- 手工输入重量需要独立权限和原因。
- 秤协议断连时不能复用上次读数。

## 7.3 计量合规

平台认证只证明技术兼容，不替代法定计量检定。实施方必须确认当地计量要求、设备检定标识和有效期；系统保存检定到期日并在过期时告警或阻断。

---

# 八、钱箱与客显

## 8.1 钱箱

- 优先通过打印机 DrawerPulse。
- 现金成功后异步触发，不把打开失败解释为收款失败。
- 非交易开箱需要权限、原因和 shf_cash_movement/审计引用。
- 每次开箱记录 device_id、actor、command_id、business_reference、结果。
- 脉冲参数只来自认证配置。
- 连续开箱设置冷却时间。

## 8.2 客显

标准消息：

- Welcome；
- CartChanged；
- PaymentRequested；
- PaymentSucceeded；
- PaymentUnknown；
- ReceiptQrCode；
- IdlePromotion。

客显只接收脱敏展示模型，不读取订单数据库。会员手机号、支付码、卡号和内部成本不得展示。

副屏断开不影响主屏交易；恢复后以最新 display_revision 覆盖展示，不回放过时逐条事件。

---

# 九、Android 平台规范

## 9.1 SDK 与 ABI

- minSdk、targetSdk 与支持周期在 ADR 固化，每年评审。
- 商业 V1 至少覆盖 arm64-v8a；armeabi-v7a 仅为存量硬件保留。
- x86_64 用于模拟器与部分测试，不作为默认商用机型。
- 厂商 so 必须记录 ABI、NDK、页面大小和签名兼容。
- 不允许因单一厂商陈旧 SDK 长期锁死 targetSdk。

## 9.2 USB

- 运行时探测 UsbManager 与硬件 feature。
- 设备筛选优先 VID/PID + interface class，不匹配所有 USB。
- 权限请求与 Activity 生命周期解耦。
- 接收 attach/detach 并安全关闭 endpoint。
- 每次连接校验序列号、接口和固件。
- 断开立即取消未完成 IO，Adapter 状态转 DISCONNECTED。

## 9.3 Bluetooth

- Android 12+ 使用 BLUETOOTH_SCAN/CONNECT 权限。
- 不需要扫描时只连接已配对设备，减少权限与隐私范围。
- 配对、连接、服务发现和业务会话分开建模。
- 连接重试有上限，不能在后台无限扫描耗电。
- 蓝牙打印默认不是高峰门店首选，必须通过干扰和断连测试。

## 9.4 后台与生命周期

- 关键设备绑定使用生命周期安全的 Service。
- 需要长期运行时遵循前台服务类型和通知要求。
- WorkManager 用于可延迟诊断、日志和非实时同步，不用于实时打印。
- Activity 重建不得重复执行硬件命令。
- Flutter engine 重启后从持久化队列恢复。

## 9.5 Kiosk 与 MDM

- 商用专用机推荐 Fully Managed Device + DPC/EMM。
- Lock task allowlist POS、支付辅助和必要系统组件。
- 禁用未知来源、USB 调试、非授权设置和用户切换。
- 保留受控网络、时间、亮度、重启和远程支持入口。
- 不依赖普通 screen pinning 作为防逃逸方案。

## 9.6 OTA

- 应用包签名一致，发布通道分生产/灰度/测试。
- 支持分批比例、门店窗口、最低版本和强制期限。
- 更新前检查电量、空间、未同步交易和未知支付。
- 不在营业中强制重启。
- Schema/Adapter 先向后兼容，再切换。
- 失败可回滚到上一兼容版本；涉及数据库不可逆迁移时必须有恢复设计。

---

# 十、厂商 Adapter 规范

## 10.1 接口

~~~kotlin
interface DeviceAdapter {
    val adapterId: String
    val adapterVersion: String
    fun supports(probe: DeviceProbe): Boolean
    suspend fun discover(context: DiscoveryContext): List<DeviceDescriptor>
    suspend fun connect(deviceId: String): DeviceSession
    suspend fun execute(session: DeviceSession, command: DeviceCommand): DeviceResult
    suspend fun health(session: DeviceSession): DeviceHealth
    suspend fun close(session: DeviceSession)
}
~~~

## 10.2 隔离

- 每个厂商 Adapter 独立 Gradle module。
- 厂商 AAR/JAR/so 不进入通用 contract。
- 依赖版本、许可证、哈希和来源进入 SBOM。
- 反射调用仅在不可避免时使用，并有契约测试。
- 厂商全局单例由 Adapter 封装。
- 回调转换为协程/Flow 时处理取消、泄漏和线程切换。
- 厂商 SDK 崩溃不得拖垮交易进程；高风险 SDK 可迁移独立进程。

## 10.3 Quirk

兼容性差异使用稳定 quirk code：

- PRINT_NEEDS_EXTRA_FEED；
- QR_NATIVE_BROKEN；
- USB_PERMISSION_LOST_AFTER_REBOOT；
- SCALE_STABLE_FLAG_INVERTED；
- DRAWER_PULSE_ONLY_AFTER_PRINT；
- CUSTOMER_DISPLAY_REQUIRES_KEEPALIVE。

Quirk 只能在认证型号/固件范围启用，不得按模糊品牌全局生效。

---

# 十一、Edge Agent 协议

## 11.1 使用条件

- Android 无法直接访问硬件；
- 厂商只提供 Windows/Linux 驱动；
- 门店需共享打印或局域网代理；
- 需要驱动崩溃隔离。

## 11.2 安全

- 仅监听 loopback 或受控局域网。
- 首次配对使用一次性码与证书。
- POS 到 Agent 使用 TLS/mTLS。
- 每个命令含 command_id、payload_hash、TTL 和权限。
- Agent 不保存支付密钥和业务主数据全量。
- 升级包签名、灰度和回滚。

## 11.3 语义

Agent 暴露与 Device Gateway 相同能力和错误码。POS 不应知道具体驱动是本机 Kotlin 还是 Agent，实现可以按设备路由切换。

---

# 十二、错误码

| 错误码 | 可重试 | 含义 |
|---|---:|---|
| DEVICE_NOT_FOUND | 是 | 设备未发现 |
| DEVICE_PERMISSION_REQUIRED | 否 | 需要系统权限 |
| DEVICE_PERMISSION_DENIED | 否 | 权限被拒绝 |
| DEVICE_BUSY | 是 | 设备占用 |
| DEVICE_DISCONNECTED | 是 | 连接断开 |
| DEVICE_UNSUPPORTED | 否 | 型号/固件不兼容 |
| ADAPTER_NOT_FOUND | 否 | 无匹配 Adapter |
| ADAPTER_VERSION_INCOMPATIBLE | 否 | 契约不兼容 |
| PRINT_PAPER_OUT | 是 | 缺纸 |
| PRINT_COVER_OPEN | 是 | 开盖 |
| PRINT_CUTTER_ERROR | 是 | 切刀异常 |
| PRINT_RESULT_UNKNOWN | 需人工 | 无法确认是否已打印 |
| SCAN_DATA_INVALID | 否 | 条码输入非法 |
| SCALE_UNSTABLE | 是 | 重量未稳定 |
| SCALE_OVERLOAD | 否 | 超量程 |
| SCALE_CALIBRATION_EXPIRED | 否 | 检定/校准过期 |
| DRAWER_OPEN_FAILED | 是 | 开箱失败 |
| EDGE_AGENT_UNREACHABLE | 是 | Agent 不可达 |

错误文本可本地化，业务只依赖稳定错误码和 retryable。

---

# 十三、诊断与隐私

## 13.1 诊断包

包括：

- Android/固件/硬件型号；
- Adapter 与 SDK 版本；
- 能力探测结果；
- 设备连接状态历史；
- 最近错误码、时长和脱敏堆栈；
- USB VID/PID、接口信息；
- 打印测试内容哈希，不含完整交易；
- 数据库和同步健康摘要；
- 配置版本。

## 13.2 隐私

- 不采集完整小票、支付码、会员手机号或银行卡数据。
- 扫码原始值默认不进入通用日志。
- 日志按 tenant_id、store_id、device_id 授权访问。
- 远程日志上传需显式策略、保留期和审计。
- 厂商 SDK 外联域名、遥测和权限必须评审。

---

# 十四、硬件认证体系

## 14.1 认证级别

| 级别 | 名称 | 含义 |
|---|---|---|
| L0 | Registered | 已登记规格，未承诺兼容 |
| L1 | Compatible | 核心能力实验室通过，限定版本 |
| L2 | Commercial Certified | 完整门店场景、长稳、升级与故障测试通过 |
| L3 | Strategic Certified | L2 + 联合维护、备件、版本通知与 SLA |

对外“支持”清单只能列 L1 以上；默认推荐采购清单只列 L2/L3。

## 14.2 认证对象

认证组合不是单一型号，而是：

终端型号 + 硬件修订 + Android 构建/固件 + 外设型号 + Adapter/SDK 版本 + POS 版本。

任一关键项变化都触发影响评估；不能把旧认证自动扩展到新固件。

## 14.3 供应商资料

- 公司和售后联系人；
- 产品规格、BOM 和硬件修订规则；
- Android 版本、补丁和升级承诺；
- SDK/AAR/JAR/so、API 文档、Demo；
- 许可证、漏洞通告和 SBOM；
- 外设协议和错误码；
- 量产一致性声明；
- 保修、备件、RMA 和停产通知；
- 远程管理能力；
- 样机与生产机差异。

## 14.4 实验室测试

| 维度 | 最低内容 |
|---|---|
| 安装 | 首装、覆盖、卸载重装、权限 |
| 启动 | 冷启动、重启自启、Kiosk |
| 显示 | 分辨率、密度、横竖屏、触控 |
| 性能 | CPU、内存、存储、温升 |
| 网络 | Wi-Fi、以太网、4G、切换、断网 |
| 打印 | 中文、二维码、切刀、缺纸、1000 单 |
| 扫码 | 常用码制、快速连扫、长码、前导零 |
| 称重 | 稳定、去皮、单位、超载、断连 |
| 钱箱 | 交易/非交易开箱、频率 |
| 客显 | 插拔、休眠、隐私 |
| 电源 | 突断电、低电压、恢复 |
| 升级 | POS、SDK、固件、回滚 |
| 安全 | 调试、Root、签名、证书、端口 |

## 14.5 长稳

L2 最低建议：

- 连续运行 7 天；
- 自动模拟不少于 10,000 笔交易；
- 打印不少于 5,000 张或等价耐久量；
- 每日断网/恢复、打印机插拔、应用重启；
- 内存无持续不可解释增长；
- 无丢单、重复硬件命令和数据库损坏；
- 关键故障能自恢复或给出明确人工步骤。

## 14.6 环境

按目标行业确认：

- 温湿度；
- 油烟、粉尘和液体；
- 静电；
- 电源波动；
- 网络干扰；
- 触控手套；
- 屏幕亮度；
- 连续营业时长。

需要专业安规、EMC、防爆或计量认证时，以供应商法定证书为准，平台测试不替代。

---

# 十五、认证门禁与发布

## 15.1 通过标准

- P0/P1 缺陷为 0。
- 核心交易、打印、扫码、钱箱、离线和恢复用例通过。
- 兼容矩阵、已知限制和安装手册完整。
- SDK 许可证和安全扫描通过。
- 固件/SDK/POS 升级路径验证。
- 厂商提供版本变更通知机制。
- 生产诊断和回滚能力可用。

## 15.2 条件通过

仅允许：

- 不影响交易的次要显示问题；
- 有明确绕过、影响范围和修复版本；
- 对外支持清单明确限制；
- 风险负责人批准。

## 15.3 失效

以下情况暂停认证：

- 厂商未通知更换主板/打印模组；
- 固件强更导致关键能力失败；
- Android 安全补丁长期停止；
- SDK 存在高危漏洞且无缓解；
- 量产批次与样机不一致；
- 售后和备件无法满足承诺；
- 生产故障率超过阈值。

---

# 十六、自动化测试与硬件实验室

## 16.1 契约测试

每个 Adapter 必须运行同一套：

- capability discovery；
- connect/disconnect；
- 幂等与超时；
- 错误映射；
- 线程与取消；
- 资源释放；
- 重启恢复；
- 日志脱敏；
- contract version 兼容。

## 16.2 硬件在环

实验室节点包括：

- 可远程重启 Android 终端；
- USB 电源控制；
- 纸张/开盖模拟或人工测试；
- 条码播放屏/扫码机器人；
- 网络故障注入；
- 视频取证；
- 测试小票 OCR/图像比对；
- 电子秤标准砝码流程。

## 16.3 发布回归

- 每个 POS 候选版本跑 L2 推荐机型。
- 厂商 Adapter 变更跑对应全量设备。
- Flutter/Android targetSdk 升级跑所有权限与后台场景。
- 规则或模板变更至少跑代表打印机。
- 硬件认证结果进入发布 Go/No-Go。

---

# 十七、性能与 SLO

| 指标 | 商业 V1 建议 |
|---|---|
| 扫码到事件 P95 | 小于 100 ms |
| 内置打印首字节 P95 | 小于 300 ms |
| 80 mm 小票完成 P95 | 小于 3 s |
| 电子秤稳定读数转发 | 小于 100 ms |
| 设备断开感知 | 小于 3 s |
| 可恢复断连自动恢复 | 小于 10 s |
| Adapter 崩溃率 | 每万次命令低于约定阈值 |
| 硬件命令日志覆盖率 | 100% 有 command_id |

具体阈值在硬件认证报告按型号固化。

---

# 十八、验收测试

## 18.1 通用

1. 设备不存在、权限拒绝、连接断开。
2. 命令重复提交和应用重启。
3. UI Activity 重建。
4. 厂商回调重复、乱序、迟到。
5. 设备执行成功但响应丢失。
6. 设备忙与并发命令。
7. 低内存、低存储、系统杀进程。
8. Android 升级和 targetSdk 升级。
9. Kiosk 逃逸、系统弹窗和时间修改。
10. 日志敏感信息扫描。

## 18.2 交易链路

- 现金收款成功、打印失败；
- 打印成功、切刀失败；
- 缺纸后补纸重试；
- 补打审计；
- 钱箱失败但交易保持成功；
- 扫码与称重快速交替；
- 断网状态下所有核心硬件可用；
- 网络恢复和包更新不阻塞硬件。

## 18.3 认证产物

- 设备兼容报告；
- 能力清单 JSON；
- 固件/SDK/POS 版本矩阵；
- 已知问题与限制；
- 安装、升级、回滚和排障手册；
- 长稳报告；
- 安全与许可证报告；
- 认证有效期与复审日期。

---

# 十九、商业 V1 决策摘要

1. Flutter 不直接依赖厂商 SDK，统一通过 Device Gateway。
2. Android 原生适配使用 Kotlin，类型桥接优先 Pigeon 或受控 MethodChannel。
3. 每个设备能力独立版本化并运行同一契约测试。
4. 打印、钱箱和客显故障不得回滚成功交易。
5. 电子秤只接受稳定、未过期、Decimal 表达的读数。
6. 专用终端采用 DPC/EMM + lock task，不能用普通屏幕固定代替。
7. Edge Agent 只在 Android 无法直接驱动或需要共享硬件时启用。
8. 认证以“型号 + 硬件修订 + 固件 + SDK + POS 版本组合”为对象。
9. 对外推荐只列 L2/L3，量产变更触发重新评估。
10. 商业发布必须经过真实硬件长稳、断电、弱网、升级和安全回归。

本规范批准后，Device Gateway 契约、能力代码、错误码、Adapter 边界和认证级别成为商业 V1 冻结接口；厂商或固件差异只能在 Adapter/Quirk 层处理。
