# ADR-038：Gate 6F 外部执行准入、完整 Alpha UAT 与发布准备边界

## 状态

Accepted（2026-08-21，项目发起人按 `CONDITIONAL GO` 批准）

## 上下文

Gate 6E 已形成可重复的内部 Alpha 候选，但支付沙箱、真实硬件和设计伙伴尚无完整受控资料，也没有任何外部执行证据。内部实现完成度不能证明 Provider、设备或伙伴场景可用，更不能替代完整 Alpha UAT 与商业发布批准。

## 决策

1. Gate 6F 从 Gate 6E 封板提交 `0dd20e90c32914da48b3154f0bf1781ab8f2ba71` 建立独立准备分支，不修改正式业务运行时。
2. 支付、硬件、伙伴使用三套独立材料包、独立验真状态和独立执行准入报告；任何一轨的公开资料、内部 Fake 或软件执行不能替代另一轨或解除阻断。
3. 拉卡拉作为首接支付的“文档核验候选”冻结；商米 T3 PRO MAX 系列与 iMin D4 Pro 系列分别作为主认证和兼容“文档核验候选”冻结。候选冻结不等于合同签署、精确 SKU、授权沙箱、样机、SDK 授权或执行准入。
4. 外部材料按 `MISSING → RECEIVED_UNVERIFIED → VERIFIED_DOCUMENT → EXECUTION_APPROVED → EXECUTED_EXTERNAL` 单向提升。达到 `VERIFIED_DOCUMENT` 只允许提交评审；只有项目发起人逐轨确认后才能进入执行。
5. `T2-UAT-001` 在 `PAY/HWD/PAR` 三项均完成独立执行解阻前保持 `DRAFT/NO-GO`。内部 Alpha 候选不计为完整 Alpha UAT。
6. `T2-REL-001` 在完整 Alpha UAT 被接受、商业许可证关闭且生产发布条件通过联合评审前保持 `DRAFT/NO-GO`。
7. 真实 Secret、PII、生产密钥、商户/终端标识、设备证书和伙伴联系方式只允许存在于批准的受控系统；Git/CI 只保存不透明引用、摘要和非敏感判定。

## 后果

- 内部产品开发成果得到保留，外部不确定性被隔离在可审计的解阻轨中。
- 现在可以准备完整测试矩阵、发布物清单和许可证关闭计划，但不能启动 Provider 网络、真实设备命令、现场试点、完整 Alpha UAT 或生产发布。
- 候选更换、资料过期或摘要不一致必须形成新记录，不得覆盖历史证据。

## 验证

- Gate 6F 机器门禁核对 RTM 状态、候选选择、必需材料、执行计数、允许变更路径、Secret 模式和 UAT/发布 NO-GO。
- Ubuntu 与 Windows 分别复核 UTF-8 文档、JSON 契约和状态守恒；最终证据索引记录全部输入的 SHA-256。
