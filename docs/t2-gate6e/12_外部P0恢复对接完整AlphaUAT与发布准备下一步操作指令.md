# 外部 P0 恢复对接、完整 Alpha UAT 与发布准备下一步操作指令

以下指令供项目发起人在确认 Gate 6E 后使用，不因本文档自动生效：

```text
我确认《T2 Gate 6E / Sprint S16 周门禁暨内部 Alpha 候选收口报告》，接受 Gate 6E CONDITIONAL PASS。

同意将以下需求由 VERIFIED 更新为 ACCEPTED：

- T2-ADM-002
- T2-POS-009
- T2-E2E-002

明确保留证据边界：T2-E2E-002 仅为 INTERNAL_ALPHA_CANDIDATE，不更新或替代 T2-UAT-001，不代表 SANDBOX、REAL_DEVICE、PILOT、FULL_ALPHA、PRODUCTION 或商业 SLA。

按 CONDITIONAL GO 启动 T2 Gate 6F：外部 P0 恢复对接与完整 Alpha UAT-Prep。继续以 Gate 6E 最终封板提交作为工作分支起点，先创建独立准备分支，不启动生产发布。

一、按三条独立轨道恢复外部解阻：

1. T2-PAY-002：选定首接 Provider，收集并离线核验授权沙箱、测试商户/终端、官方接口版本、签名验签、回调、退款、账单、限流、网络白名单和技术联系人。资料达到 VERIFIED_DOCUMENT 后先提交独立《支付沙箱执行准入评审报告》；未经我确认不得网络调用。
2. T2-HWD-001：确定一个主认证机型和一个兼容机型，收集 Android/固件、CPU/内存、SDK、样机，以及两种打印、扫码、电子秤、钱箱、客显和 APK/升级资料。资料达到 VERIFIED_DOCUMENT 后先提交独立《真实硬件执行准入评审报告》；未经我确认不得安装、重启或下发命令。
3. T2-PAR-001：建立 5 家真实目标并取得至少 3 家可验证书面试点意愿，完成脱敏样本授权、保留/删除、旧系统对账和现场责任边界。资料达到 VERIFIED_DOCUMENT 后先提交独立《设计伙伴执行准入评审报告》；未经我确认不得联系门店开展现场执行。

三轨继续保持 BLOCKED，分别达到 VERIFIED_DOCUMENT 也不得自动解除；必须逐轨提交执行准入评审并等待我确认。Secret、真实 PII、生产密钥和未脱敏商户数据不得进入仓库、日志、CI 或制品。

二、只允许准备 T2-UAT-001：

- 更新完整 Alpha UAT RTM、环境拓扑、角色/RACI、测试商户、门店/终端矩阵、支付/退款、设备/外设、离线/同步、升级回退、备份恢复、性能、权限、安全、对账和数据迁移用例；
- 冻结 P0/P1 缺陷标准、证据等级、签署人、Go/No-Go、数据清除和环境销毁规则；
- 在 PAY/HWD/PAR 三项 P0 均经独立执行解阻并确认前，T2-UAT-001 保持 DRAFT，不得启动完整 Alpha UAT。

三、T2-REL-001 仅允许发布准备：

- 编制版本清单、构建 commit、SBOM、许可证、制品签名、变更说明、数据库前向迁移、灰度/回退、监控告警、客服/实施手册和发布 Go/No-Go 模板；
- T2-LIC-001 必须建立 Aviator、simple-http、MySQL Connector/J 的替换或法务关闭计划；商业发布前未关闭则 NO-GO；
- T2-JSH-001 继续 DEFERRED，除非我另行提供真实接口资料并授权启动；
- 完整 Alpha UAT 未 ACCEPTED 前，T2-REL-001 保持 DRAFT，不得生产部署、创建商业 SLA 或宣称可商用。

四、完成上述准备后，分别提交三条外部执行准入报告、《T2完整Alpha UAT启动评审报告》和《T2发布准备差距报告》，等待我逐项确认。

未经再次确认，不得进行 Provider 网络、真实终端命令、现场试点、完整 Alpha UAT 或生产发布。
```
