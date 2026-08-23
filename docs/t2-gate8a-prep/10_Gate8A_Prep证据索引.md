# Gate 8A-Prep 证据索引

## 1. 基线与候选

- 基线：`b47533eba707d486abe44dbf70ec7b651081b3af`
- 分支：`t2/gate8a-prep-commercial-operations`
- 候选提交：`b59669b9c3948103c82d45b46b1042e593bd1ed4`
- 封存提交：待 Gate 8A-Prep 评审确认后决定；本阶段不创建 tag

## 2. 静态证据

- CR：`CR-T2G8A-001/002/003`
- ADR：`ADR-056`
- 准入、状态机、OpenAPI、事件、错误码、持久化、UI 和 46 条固定向量：
  `contracts/t2/gate8a-prep/`
- 文档：`docs/t2-gate8a-prep/`
- 机器门禁：`scripts/check_t2_gate8a_prep.py`
- 证据聚合：`scripts/build_t2_gate8a_prep_evidence.py`

## 3. CI 结果

| 项目 | 结果 |
|---|---|
| GitHub Run | `32641276144` / Success / 30s |
| governance-ubuntu | Success / Job `97198628949` / Artifact `9493658890` / `f974ace917b6e57d478d63e50e43394b9ace5720ef2b36bdcba1d6fa09b61e95` |
| governance-windows | Success / Job `97198629002` / Artifact `9493660988` / `2da9b5ee46f0927948952ff8f489366495ca4554286af6ff924944e17f7614e9` |
| scope-boundary | Success / Job `97198628930` / Artifact `9493659240` / `1350e14ec978dde96db80378c014d7d9256311b3c3709db5dc97e50be1a9de58` |
| evidence | Success / Job `97198673193` / Artifact `9493663601` / `0b0120ce74b7cfee0043df5d7ca462038ace6b729d9d04de36bd6d7d468e42c9` |

GitHub Run 页面：`https://github.com/eiven-xxw/jshPOS/actions/runs/32641276144`。

## 4. 证据边界

该索引只证明准备材料完整和边界未越权。它不证明三项运行时、生产容量、真实开户、真实计费、
商业 SLA、完整 Alpha、生产发布或可商用。Run 的四项 Node.js 20 弃用提示是 GitHub Action
运行时告警，执行器已强制使用 Node.js 24；未跳过任何步骤，后续升级固定 Action SHA 时单独治理。
