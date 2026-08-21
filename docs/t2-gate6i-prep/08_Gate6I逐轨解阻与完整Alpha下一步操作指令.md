# Gate 6I 后逐轨解阻与完整 Alpha 下一步操作指令

以下指令只用于项目发起人确认 Gate 6I-Prep；不应在真实材料缺失时直接授权执行。

```text
我确认《T2 Gate 6I-Prep 启动评审报告》，接受准备阶段 CONDITIONAL PASS / PREPARED_NO_GO。

保持 T2-PAY-002、T2-HWD-001、T2-PRN-001、T2-PAR-001 为 BLOCKED，T2-UAT-001、T2-REL-001 为 DRAFT，T2-LIC-001、T2-JSH-001 为 DEFERRED。

现仅授权对我通过批准受控渠道提供的真实不透明材料引用进行离线验真：

1. 支付轨：拉卡拉 PAY-AUTH、PAY-MERCHANT、PAY-TERMINAL、PAY-API、PAY-SIGN、PAY-CALLBACK、PAY-BILL、PAY-LIMIT、PAY-NETWORK、PAY-SECRET、PAY-CONTACT；
2. 硬件打印轨：主认证机、兼容机、两种打印、扫码、电子秤、钱箱、客显、APK升级和保修RMA受控材料；
3. 伙伴轨：5家真实目标、至少3家书面意愿、脱敏样本授权、保留删除、旧系统对账和现场责任边界；
4. 许可证轨：Aviator、simple-http、MySQL Connector/J 的使用清单、替换方案或书面法务意见、Owner和关闭证据。

逐份核验来源、签署、适用产品/精确型号/主体、版本、范围、SHA-256、保管人、生效到期、轮换、删除和吊销。Secret、真实PII、商户终端号、证书、伙伴身份联系方式和受控原文不得进入仓库、日志、CI或普通制品。

某一轨完整达到 VERIFIED_DOCUMENT 后，只提交该轨更新后的独立执行准入评审报告等待我确认，不得自动执行。PAY/HWD/PRN/PAR四项均经独立执行准入确认前，T2-UAT-001继续DRAFT且完整Alpha Run为0；T2-LIC-001未关闭且完整Alpha未ACCEPTED前，T2-REL-001继续DRAFT且生产发布为0。

未经再次确认，不得进行Provider网络、真实资金、真实终端/外设命令、伙伴联系或现场试点、完整Alpha、生产部署或商业可用声明。

完成真实材料首批离线验真后，分别提交对应轨的更新报告和《T2完整Alpha UAT启动评审报告》，等待我逐项确认。
```
