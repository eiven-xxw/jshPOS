# CR-T2G9R3-007：Owner 运营页面正式整改

## 1. 决策

- 状态：`VERIFIED_CANDIDATE / AWAITING_SPONSOR_CONFIRMATION`；
- 批次：`G9A-R3B`；
- 授权基线：`4e8a9f2b1dd52ce6b198bd3a25328e2a80330a71`；
- 分支：`t2/gate9b-sprint27d-g9a-r3b-runtime`；
- Finding：`G9A-UI-P1-001`；
- 复用：既有已接受 Requirement 与 `ADR-071`，不新增 Requirement ID。

## 2. 准入范围

只允许按 `R3B-R0 → R1 → ... → R11` 串行整改 `VUE-05..15`：共享页面状态、
VUE-08 四个最小权限、加载/空态/安全错误、关联标识、单航班、原幂等键恢复、危险操作
确认和真实组件挂载测试。

## 3. 不变量

- Vue 只调用既有正式 API，服务端继续执行可信租户、门店数据范围和最终权限；
- `UNKNOWN` 不得重建写命令；VUE-08/13/14 必须保留原对象、动作和幂等键；
- 不得复制会员、价格、促销、库存、成本、采购、调拨、发布、日结、异常、批次或开店算法；
- 不得修改 Controller/API、Owner 领域代码、依赖、表、任务或已发布迁移；
- 电子支付、设备与外设继续失败关闭，外部执行保持 0。

## 4. 验收与回退

十一页必须逐项通过路由、按钮、权限、数据范围、加载、空态、安全错误、单航班、离线适用性、
业务日适用性、原操作恢复和直接挂载测试。任一 P0/P1、跳过测试、自动重跑、范围越界或外部状态
漂移均为 NO-GO。回退仅撤销本批 Vue 与治理提交，不触碰 Owner 事实和迁移历史。

## 5. 证据边界

最高只形成 `INTERNAL_SOFTWARE_UI_INTERACTION / G9A-R3B VERIFIED_CANDIDATE`。
R3C 与 26 页联合验收完成前，`G9A-UI-P1-001` 继续 `OPEN`，不得解释为完整 Alpha、生产、
商业验收或商业 SLA。
