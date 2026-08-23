# 许可证、外部 P0、Alpha 与发布 Go/No-Go

| 项目 | 当前状态 | 执行数 | 决策 | 解阻条件 |
|---|---:|---:|---|---|
| T2-PAY-002 拉卡拉沙箱 | BLOCKED | 0 | NO-GO | 整包 VERIFIED_DOCUMENT + 独立执行批准 |
| T2-HWD-001 真实 Android 设备 | BLOCKED | 0 | NO-GO | 主/兼容机完整资料 + 独立执行批准 |
| T2-PRN-001 真实外设 | BLOCKED | 0 | NO-GO | 六类设备资料和样机 + 独立执行批准 |
| T2-PAR-001 设计伙伴 | BLOCKED | 0 | NO-GO | 5 家目标、3 家书面意愿 + 独立执行批准 |
| T2-LIC-001 商业许可证 | DEFERRED | 0/3 CLOSED | RELEASE NO-GO | Aviator、simple-http、MySQL Connector/J 逐项关闭 |
| T2-UAT-001 完整 Alpha | DRAFT | 0 | NO-GO | 四项外部 P0 独立批准且环境/RACI 冻结 |
| T2-REL-001 发布 | DRAFT | 0 | NO-GO | UAT ACCEPTED、许可证关闭、制品/回退/签署齐备 |
| T2-JSH-001 鲸熵汇连接器 | DEFERRED | 0 | DEFERRED | 用户提供真实资料并单独授权 |

Gate 8B-Prep 只允许对内部商业运营聚合形成 `CONDITIONAL PASS` 建议。完整 Alpha、生产和商业发布继续 NO-GO。
