# 外部 P0 与完整 Alpha 准备下一步操作指令

建议项目发起人确认本报告后复制以下指令。该指令只恢复外部资料与执行准入准备，仍不授权
Provider 网络、真实设备命令、现场试点、完整 Alpha 或生产发布。

```text
我确认《T2 Gate 7E / Sprint S23-A 商业V1内部汇总验收报告》，接受 Gate 7E
CONDITIONAL PASS。

同意将 T2-E2E-004 由 VERIFIED 更新为 ACCEPTED。明确该接受仅代表虚构租户、虚构终端、
现金与合成外部边界下的 INTERNAL_V1_BUSINESS_COMPLETE_CANDIDATE，不更新或替代
T2-UAT-001，不代表 SANDBOX、REAL_DEVICE、PILOT、FULL_ALPHA、PRODUCTION、
COMMERCIAL 或商业 SLA。

按 CONDITIONAL GO 启动 T2 Gate 7F-Prep：外部 P0、商业许可证与完整 Alpha 执行准入准备。

以 Gate 7E 最终封存提交
[填写 Gate 7E 最终封存提交]
作为工作分支起点，创建独立准备分支：
t2/gate7f-prep-external-p0-alpha-admission

本阶段只允许准备和离线验真，不允许任何外部执行：

1. T2-PAY-002：首接拉卡拉；补齐授权沙箱、测试商户/终端、官方接口版本、签名验签、
   回调、退款、账单、限流、网络白名单、Secret 保管和技术联系人。达到 VERIFIED_DOCUMENT
   后提交独立《支付沙箱执行准入评审报告》，未经我确认不得网络调用。
2. T2-HWD-001：确定一个主认证 Android 机型和一个兼容机型，补齐型号、固件、CPU/内存、
   SDK、样机、APK 安装/升级和技术联系人。达到 VERIFIED_DOCUMENT 后提交独立
   《真实硬件执行准入评审报告》，未经我确认不得安装、重启或下发命令。
3. T2-PRN-001：补齐两种打印方案、钱箱、客显、扫码和电子秤的协议、SDK、样机、驱动、
   错误码、纸型/编码、兼容矩阵和验收责任人。达到 VERIFIED_DOCUMENT 后提交独立
   《真实外设执行准入评审报告》，未经我确认不得调用真实外设。
4. T2-PAR-001：建立5家真实设计伙伴并取得至少3家可验证书面试点意愿，核验脱敏样本授权、
   保留/删除、旧系统对账、现场责任和退出规则。达到 VERIFIED_DOCUMENT 后提交独立
   《设计伙伴执行准入评审报告》，未经我确认不得联系门店开展现场执行。
5. T2-LIC-001：为 Aviator、simple-http、MySQL Connector/J 形成替换、采购或法务关闭的
   逐项证据、负责人和截止点；未关闭前商业发布 NO-GO，不得用“已知风险”替代结论。
6. T2-UAT-001 只允许冻结完整 Alpha 的 RTM、环境、RACI、支付/退款、设备/外设、离线同步、
   升级回退、备份恢复、迁移、性能、安全、对账、P0/P1、证据等级、签署和数据清除方案。
7. PAY/HWD/PRN/PAR 四轨未分别经独立执行准入并由我确认前，T2-UAT-001 继续 DRAFT，
   不得启动完整 Alpha；T2-REL-001 继续 DRAFT，不得生产发布。

T2-SAA-001、T2-SUB-001、T2-SVC-001 继续 DRAFT；如需进入商业 V1，必须分别提交独立 CR、
价值与范围影响分析，未经确认不得开发。T2-JSH-001 继续 DEFERRED，等待我提供真实接口资料。

Secret、商户号、终端号、证书、真实 PII 和未脱敏商户数据不得进入 Git、普通日志、CI 或
普通制品。内部合成证据不得解除 SANDBOX、REAL_DEVICE、REAL_PERIPHERAL 或 PILOT 阻断。

完成后分别提交四条外部执行准入报告、《T2-LIC-001商业许可证关闭计划》和
《T2完整Alpha UAT启动评审报告》，等待我逐项确认。未经再次确认，不得进行 Provider
网络、真实资金、真实设备/外设命令、伙伴现场、完整 Alpha、生产部署或商业可用声明。
完成后为我整理下一步操作指令。
```

