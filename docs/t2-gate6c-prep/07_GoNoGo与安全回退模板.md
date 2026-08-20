# Gate 6C Go/No-Go 与安全回退模板

## 1. 单轨决策

每条外部轨只允许：

- `GO_EXECUTION`：全部必需文档已验证，执行范围、负责人、安全、回退和证据方案已冻结，且项目发起人已确认。
- `CONDITIONAL_GO_DOCUMENT_ONLY`：允许继续补件和离线验真，禁止网络/设备/现场执行。
- `NO_GO`：授权、来源、版本、安全或 P0 输入缺失/冲突。

当前三条轨均为 `NO_GO`（执行层）和 `CONDITIONAL_GO_DOCUMENT_ONLY`（资料层）。

## 2. 完整 Alpha 决策规则

以下任一为真即 `NO-GO`：

1. `T2-PAY-002/HWD-001/PAR-001` 任一不是 ACCEPTED；
2. P0/P1 开放缺陷、无法解释资金/库存/租户差异或高危/严重漏洞不为 0；
3. Secret、样本、设备或证据的授权/保管/删除不可验证；
4. 主支付 UNKNOWN、退款、回调、账单对账或实机升级/回退未按批准矩阵执行；
5. 便利店、零食折扣店、社区超市没有覆盖设计伙伴和对账口径；
6. UAT 环境、数据、RACI、事故响应、停止条件和恢复点未冻结。

`CONDITIONAL GO` 不得用于绕过上述 P0，只能限制非 P0 范围、时间或样本量，并写明到期、责任人和自动失效条件。

## 3. 签署模板

| 角色 | 姓名/引用 | 决策 | 限制/风险 | 时间 |
|---|---|---|---|---|
| SP | 待填 | NOT_REVIEWED |  |  |
| PO | 待填 | NOT_REVIEWED |  |  |
| ARCH | 待填 | NOT_REVIEWED |  |  |
| QA | 待填 | NOT_REVIEWED |  |  |
| SEC | 待填 | NOT_REVIEWED |  |  |
| SRE | 待填 | NOT_REVIEWED |  |  |
| PAY/DEV/BIZ/FIN | 按范围待填 | NOT_REVIEWED |  |  |

## 4. 安全回退

- 支付：停止请求与回调入口、吊销/轮换沙箱凭据、隔离报文/账单、按原请求号查询在途 UNKNOWN，禁止重发扣款。
- 硬件：停止任务、撤销设备凭据、恢复批准 APK/配置，失败设备隔离并走 RMA；禁止未知固件继续运行。
- 伙伴：停止导入/测试、撤销访问、导出必要审计后删除样本并出具证明，通知授权方。
- UAT：冻结新会话，保留不可变事实和证据，按 Owner 对账，恢复环境到批准快照；不得用报表或 POS 本地库覆盖云端权威事实。

## 5. 评审记录最小字段

decision_id、scope、baseline_commit、requirements、evidence_manifest_sha256、open_risks、exceptions、expiry、rollback_owner、required_signatures、decision、decided_at。任何后补或变更必须新建决策记录，不覆盖历史签署。
