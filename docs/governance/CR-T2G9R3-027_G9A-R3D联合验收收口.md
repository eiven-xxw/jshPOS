# CR-T2G9R3-027：G9A-R3D 联合验收收口

## 决策

按已批准 R3D-R4 执行全部 26 页联合验收汇总和完整既有门禁，不新增 Requirement 或业务能力。

## 范围

- 汇总 20 个 Vue、6 个 Flutter 页面和 12 个既有验收维度；
- 固化 3 条联合旅程、3 个已关闭 P1 seed 和 R0 至 R3 串行提交；
- 新增治理、Server、Web、Flutter 双平台、Android/数据库、安全和证据聚合 CI；
- 仅在完整 CI 全绿后形成 `VERIFIED_CLOSURE_CANDIDATE` 建议。

## 不变量

- `G9A-UI-P1-001` 在发起人确认前保持 `OPEN`；
- RTM、Requirement、服务端 API、依赖、数据库和已发布迁移不变；
- 外部 PAY/HWD/PRN/PAR、UAT/REL、LIC/JSH 状态和零执行边界不变；
- 失败 Run 必须保留，不得重跑掩盖、跳过或降低阈值。
