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

候选提交 `135a39e7adb03bb7b98594dd3bfc4aaaf4d17004` 的 Run
[`32721705711`](https://github.com/eiven-xxw/jshPOS/actions/runs/32721705711) 已全绿。

| Producer | Job ID | Artifact ID | Artifact SHA-256 |
|---|---:|---:|---|
| governance-ubuntu | 97414314002 | 9517968312 | `b038108aa7b34e2c6e7e53105c4a0ab42535ac68e9be8c8ee883f14bafe0bf17` |
| governance-windows | 97414313797 | 9517979620 | `8a1b8d717f855068154f5aa89c2876899fd013fb611bde94c5d0cd47cf653fd3` |
| offline-boundary | 97414314063 | 9517968354 | `080d6cbf0e8cd3c66bd66c492137889e482f6b8a9cd82c37b0d8c22818044dbf` |
| evidence | 97414467979 | 9517987402 | `f41b96743d02e41a9d3ea4c09be5a81d580779d61ef5358bde9658fa967e2dcc` |

绿色只证明准备包和边界可重复，不证明任何外部材料存在或可执行。封存回填提交仍需复跑
同一完整工作流，确保报告回填没有破坏门禁。
