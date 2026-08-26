# 三业态与 SaaS 旅程封存

## 1. 正式内部运行栈

来源 G9A-R4 证据在同一提交、同一运行窗口装配 MySQL、Redis、商业 JAR、HTTP/OpenAPI、
Vue dist、Flutter POS 和文件 SQLite。Flutter 连接正式 JAR，禁止测试内置 HTTP Server、
Mock/InMemory Owner 和直接数据库业务写入。

## 2. 三业态

- `CONVENIENCE`：便利店主路径；
- `SNACK_DISCOUNT`：零食折扣店模板；
- `COMMUNITY_SUPERMARKET`：社区超市及可选批次效期能力。

三条旅程覆盖 22 Owner、66 个检查点、36 项逐字段守恒和 12 个固定故障 seed。金额、数量、
优惠、库存、成本、会员权益、同步、报表与日结必须沿用各 Owner 冻结规则。

## 3. 商业运营旅程

SAA/SUB/SVC 通过正式 MySQL、Redis、JAR 与 HTTP API 完成虚构商户申请审批、套餐发布、
租户激活、订阅续期/宽限/降级/恢复、实施检查和服务工单。最高结论仍为
`INTERNAL_COMMERCIAL_OPERATIONS_CANDIDATE`，不代表真实计费、通知或合同 SLA。

## 4. 故障语义

重复、乱序、ACK 丢失、重启、部分 Owner 失败和 UNKNOWN 必须复用原事实或命令身份收敛；
不得重新生成扣款、退款、订单、库存或成本命令。外部支付与设备端口继续失败关闭。
