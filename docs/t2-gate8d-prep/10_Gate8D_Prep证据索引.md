# Gate 8D-Prep 证据索引

## 1. 基线与范围

- 工作基线：`bd1dee42bacfb75874d601e16828c8f82720986b`
- 分支：`t2/gate8d-prep-external-p0-license-alpha-admission`
- 证据等级：`STATIC_GOVERNANCE_AND_OFFLINE_METADATA_REVIEW`
- 运行时、迁移和依赖变更：0

## 2. 机器判定

- T2-RDY-001：`ACCEPTED`，证据上限不变；
- PAY `0/11`、HWD `0/2`、PRN `0/6`、PAR `0/5`、书面意愿 `0/3`；
- LIC `CLOSED 0/3`；UAT/REL `DRAFT/NO_GO`；
- Provider 网络、真实资金、设备/外设命令、伙伴联系/现场、完整 Alpha、生产部署、商业 tag/
  声明全部为 0。

## 3. CI 证据

| Producer | 预期 | Run/Artifact/SHA-256 |
|---|---|---|
| governance-ubuntu | RTM、合同、UTF-8、ADR/CR、状态守恒 | AWAITING_CI |
| governance-windows | 双平台复现 | AWAITING_CI |
| offline-boundary | 范围、Secret/PII、依赖差异、零执行 | AWAITING_CI |
| evidence | 不可变证据索引 | AWAITING_CI |

CI 全绿后只把 `AWAITING_CI` 回填为可核验 Run、Artifact ID 和 SHA-256；绿色只证明准备包和
边界可重复，不证明任何外部材料存在或可执行。
