# Gate 7F-Prep 证据索引

## 1. 候选身份

- 工作基线：`3aaa92e9c90d1db540cfb6b70cdf65058c6a118f`
- 候选提交：`302bf3b6b37dd5c84462be596015337d0539d357`
- GitHub Actions Run：`32631466112`
- 总时长：46 秒
- 结论：`PASS / STATIC_GOVERNANCE_AND_OFFLINE_METADATA`

## 2. Job 与 Artifact

| Job | 时长 | Artifact ID | SHA-256 |
|---|---:|---:|---|
| governance-ubuntu | 11s | 9491152813 | `ba6832c93630c5424ee6e152eea8970e394f3721b9d5e742bc3882c6d8f9a689` |
| governance-windows | 25s | 9491155822 | `e089dfbc83c096b3df8cc163653a169e5641a82535db91a649f1f07267371f7b` |
| offline-boundary | 9s | 9491152323 | `bf81b8c0957ea14e726e0bba063d8f6a1c07e1578c056c3435f7e23cf417075d` |
| evidence | 13s | 9491158914 | `1485275dea0fdb69bf7b6d9585f4089bc7cc5a514726c7fc28e951ba46f293dc` |

## 3. 机器判定摘要

- `T2-E2E-004 = ACCEPTED`，证据上限保持内部候选；
- 支付 `0/11`、硬件 `0/2`、外设 `0/6`、伙伴 `0/5`、书面意愿 `0/3`；
- 许可证关闭 `0/3`，UAT 测试域 `16`；
- 四条外部 Requirement 仍 `BLOCKED`，UAT/REL 仍 `DRAFT`，LIC/JSH 仍 `DEFERRED`；
- Provider 网络、真实资金、设备/外设命令、伙伴联系、现场、完整 Alpha、生产和商业声明均为 0。

## 4. 证据限制

本索引不包含受控原文、Secret、商户/终端号、设备序列号、联系人或真实 PII。Artifact 的
绿色状态只代表治理与离线元数据门禁通过，不解除 `SANDBOX/REAL_DEVICE/REAL_PERIPHERAL/
PILOT` 阻断。
