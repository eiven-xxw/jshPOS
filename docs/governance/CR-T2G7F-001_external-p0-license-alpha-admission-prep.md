# CR-T2G7F-001：外部 P0、商业许可证与完整 Alpha 执行准入准备

- 状态：APPROVED_PREP_ONLY
- 日期：2026-08-23
- 发起人：项目发起人
- 基线：`3aaa92e9c90d1db540cfb6b70cdf65058c6a118f`
- 分支：`t2/gate7f-prep-external-p0-alpha-admission`

## 变更原因

Gate 7E 已证明商业 V1 内部业务候选可装配，但商用落地仍缺支付沙箱、真实主机、真实外设、
设计伙伴、许可证关闭和完整 Alpha。需要把历史收件模板升级为与当前候选绑定、逐轨独立、
机器可校验且不会误解除外部阻断的执行准入包。

## 获批范围

- 接受 `T2-E2E-004`，保留其内部候选证据上限；
- 四轨受控材料清单和离线元数据验真；
- 独立执行准入报告、许可证关闭计划、完整 Alpha 环境/RACI/测试/证据/销毁冻结；
- 双平台治理、范围/Secret、依赖差异和证据索引门禁。

## 非目标与影响

不修改业务代码、数据库、依赖、外部适配器或生产配置；不调用 Provider，不操作设备/外设，
不联系伙伴，不启动完整 Alpha 或生产。四条外部需求继续 `BLOCKED`，UAT/REL 继续 `DRAFT`，
LIC/JSH 继续 `DEFERRED`，SAA/SUB/SVC 继续 `DRAFT`。

## 验收

RTM、ADR、合同、报告和目录一致；所有缺件保持 `MISSING/NOT_ACHIEVED/NO-GO`；外部执行
计数全部为 0；运行时和依赖差异为 0；Ubuntu、Windows、离线边界和证据索引门禁通过。
