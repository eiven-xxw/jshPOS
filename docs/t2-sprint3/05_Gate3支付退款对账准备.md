# Gate 3 支付、退款与对账准备

## 1. 本 Sprint 结论

`T2-PAY-001`、`T2-PAY-003`、`T2-REF-001`、`T2-REC-001` 只冻结 Provider 无关语义，状态继续 `DRAFT`。`T2-PAY-002` 没有授权沙箱，继续 `BLOCKED`。本 Sprint 支付/退款/对账数据库表、Controller、调度器、SDK、回调端点和网络调用均为 0。

## 2. 待冻结状态

- Payment：`CREATED/PROCESSING/UNKNOWN/SUCCEEDED/FAILED/CANCELLED/CLOSED/PARTIALLY_REFUNDED/REFUNDED`；
- PaymentAttempt：`CREATED/SENT/ACCEPTED/CONFIRMED/FAILED/TIMED_OUT/UNKNOWN/CANCELLED`；
- Refund：`CREATED/PENDING_APPROVAL/PROCESSING/UNKNOWN/SUCCEEDED/FAILED/CANCELLED/CLOSED`；
- Reconciliation：`OPEN/INVESTIGATING/WAITING_PROVIDER/RESOLVED/APPROVED/CLOSED`。

成功资金事实不可倒退；超时进入 UNKNOWN，只能查询原 channel_request_no、接收幂等回调或对账，禁止自动第二次扣款。退款累计金额和数量不得超过原成功事实，优惠按原成交快照回退。

## 3. 解阻输入

主支付合作方、测试商户/门店/终端、授权沙箱账号、接口与错误码文档、请求签名/验签、回调公网与白名单、测试证书/密钥安全发放方式、限频/SLA、测试账单、退款能力、技术联系人、数据与日志合规边界、联合验收矩阵和停用/轮换方式必须齐备。

资料只能进入受控 Secret/测试环境，仓库只保存变量名和合成样例。资料齐备后先提交 `T2-PAY-002` 解阻评审；未经项目发起人确认不得进行网络调用。

## 4. 合成测试向量

设计必须覆盖成功、明确失败、超时 UNKNOWN、查询收敛、回调先到/重复/乱序、SUCCEEDED 后 FAILED 不倒退、FAILED 后可信 late success、金额/币种/商户不匹配隔离、部分/全部退款、退款 UNKNOWN、超额退款/数量拒绝和内部/渠道账单五类差异。所有结果仍只属于 STATIC/FAKE，不构成 SANDBOX。
