# 22 Owner 正式运行栈审计

## 审计结论

仓库当前存在 22 个 `jshpos-*` Owner 模块，均进入默认 Maven reactor 与商业 JAR 依赖装配，
且均有测试资产。模块存在与装配完整，不等于已经在同一次正式跨 Owner 旅程中被执行和对账。

| 序号 | Owner | 模块 | R4 正式旅程职责 | 当前证据 |
|---:|---|---|---|---|
| 1 | SaaS | saas | 商户、套餐、技术租户生命周期 | Gate 8B 正式 HTTP 子旅程 |
| 2 | Subscription | subscription | 续期、降级与恢复 | Gate 8B 正式 HTTP 子旅程 |
| 3 | Foundation | foundation | 可信租户、组织、门店、员工、业务日 | Gate 8B 正式 HTTP 子旅程 |
| 4 | Service | service | 实施检查、工单与附件元数据 | Gate 8B 正式 HTTP 子旅程 |
| 5 | Migration | migration | 开业资料迁移 | 独立 Owner/聚合证据 |
| 6 | Onboarding | onboarding | 模板、门店开通与检查 | 独立 Owner/聚合证据 |
| 7 | Catalog | catalog | 商品、价格、数据包 | 独立 Owner/聚合证据 |
| 8 | Sync | sync | 终端、Inbox/Outbox、ACK、游标 | 独立 Owner/聚合证据 |
| 9 | Order | order | 班次、成交与订单事实 | 独立 Owner/聚合证据 |
| 10 | Promotion | promotion | 报价、人工优惠与成交分摊 | 独立 Owner/聚合证据 |
| 11 | Member | member | 会员权益与会员价快照 | 独立 Owner/聚合证据 |
| 12 | Payment | payment | 现金及 Provider 无关资金状态 | 独立 Owner/聚合证据 |
| 13 | Inventory | inventory | 数量流水、余额、批次效期 | 独立 Owner/聚合证据 |
| 14 | Costing | costing | 只追加成本流水与投影 | 独立 Owner/聚合证据 |
| 15 | Procurement | procurement | 供应商、采购、盘点、补货 | 独立 Owner/聚合证据 |
| 16 | Transfer | transfer | 发出、在途、收货与差异 | 独立 Owner/聚合证据 |
| 17 | Returns | returns | 原单退货退款与换货 Saga | 独立 Owner/聚合证据 |
| 18 | Reporting | reporting | 可重建投影与逐日对账 | 独立 Owner/聚合证据 |
| 19 | Operations | operations | 日结与异常中心 | 独立 Owner/聚合证据 |
| 20 | Resilience | resilience | 合成备份恢复 | 独立 Owner/聚合证据 |
| 21 | Release | release | 合成升级与安全前向修复 | 独立 Owner/聚合证据 |
| 22 | Integration | integration | 组合根与跨 Owner 契约治理 | 正式栈启动/健康烟测 |

## 两条现有证据链的实际边界

### Gate 7E

CI 同一时间窗口启动 MySQL、Redis、JAR 与 Web dist，并执行文件 SQLite 的 Flutter 测试。
但 `formal_pos_runtime_e2e_test.dart` 自己绑定随机端口 `HttpServer`，终端认证、登录和数据包请求
没有到达被启动的 JAR。JAR 仅做根路径和健康探测，Web 仅做静态首页探测；因此这是
“正式组件证据 + 栈烟测”，不是三业态正式 API 全栈旅程。

### Gate 8B

CI 确实通过正式 JAR/MySQL/Redis/HTTP 完成 SAA→SUB→SVC 旅程，也验证可信租户创建和组织门店。
但该旅程没有 Flutter、文件 SQLite、三业态交易以及其余业务 Owner。

### Gap

两条证据不能在事后拼接为一个跨 Owner E2E。当前尚无“同一提交、同一运行窗口、同一可信上下文、
正式 API、三业态、22 Owner、逐检查点守恒”的证据，故 `G9A-E2E-P1-001` 继续 OPEN。
