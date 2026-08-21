# Gate 6H 后外部 P0 与完整 Alpha 下一步操作指令

复制以下指令作为下一步任务；其中材料 ID、机型和伙伴名称必须填写真实受控引用，不得使用占位文本解除阻断。

```text
我确认《T2 Gate 6H / Sprint S18 周门禁暨内部发布候选报告》，接受 Gate 6H CONDITIONAL PASS。

同意将以下需求由 VERIFIED 更新为 ACCEPTED：
- T2-UX-001
- T2-PERF-001
- T2-OPS-001
- T2-RC-001

明确证据边界：T2-RC-001 仅为 INTERNAL_RELEASE_CANDIDATE，不代表 SANDBOX、REAL_DEVICE、PILOT、FULL_ALPHA、PRODUCTION 或商业 SLA。

按 CONDITIONAL GO 启动 T2 Gate 6I-Prep：外部 P0 执行准入与完整 Alpha UAT 冻结。以 Gate 6H 最终封板提交作为准备分支起点，暂不生产发布。

1. T2-PAY-002：仅对我通过受控渠道提供的拉卡拉授权沙箱、测试商户/终端、正式接口版本、签名验签、回调、退款、账单、限流、网络白名单、Secret 系统引用和技术联系人材料做离线验真；材料达到 VERIFIED_DOCUMENT 后提交独立支付沙箱执行准入评审，未经确认不得网络调用。
2. T2-HWD-001 / T2-PRN-001：仅核验一个主认证机型、一个兼容机型、Android/固件/CPU/内存/SDK/样机，以及两种打印、扫码、电子秤、钱箱、客显和升级资料；达到 VERIFIED_DOCUMENT 后提交独立实机执行准入评审，未经确认不得安装或下发命令。
3. T2-PAR-001：核验 5 家真实目标、至少 3 家书面试点意愿、脱敏样本授权、保留/删除、旧系统对账和现场责任边界；未经确认不得联系门店开展现场执行。
4. T2-LIC-001：为 Aviator、simple-http、MySQL Connector/J 分别确定替换方案或法务批准、Owner 和截止日期；商业发布前未关闭则 NO-GO。
5. T2-UAT-001：冻结完整 Alpha 的 RTM、环境、商户/门店/终端矩阵、支付退款、硬件外设、离线同步、迁移、升级回退、备份恢复、性能、安全、对账、证据等级、数据清除、P0/P1 和签署规则；四条外部 P0 未经独立确认前继续 DRAFT，不执行完整 Alpha。
6. T2-REL-001：只更新版本、制品、SBOM、许可证、签名、迁移、灰度回退、监控、实施客服和 Go/No-Go 模板，继续 DRAFT，不生产部署。

完成后分别提交支付、硬件打印、伙伴、许可证四条执行准入报告，以及《T2完整Alpha UAT启动评审报告》，等待我逐项确认。

未经再次确认，不得进行 Provider 网络、真实终端命令、伙伴现场执行、完整 Alpha、生产部署或商业可用声明。
```
