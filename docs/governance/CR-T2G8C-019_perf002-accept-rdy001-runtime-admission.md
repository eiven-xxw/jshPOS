# CR-T2G8C-019：接受 T2-PERF-002 并准入 T2-RDY-001

## 决策

项目发起人于 2026-08-24 接受 T2-PERF-002 `CONDITIONAL PASS`，同意其由 `VERIFIED` 更新为 `ACCEPTED`，并授权从封存提交 `721130ab57a2fe2b2f024150d85e237491e5b34c` 建立独立分支 `t2/gate8c-sprint26d-rdy001-runtime`，只实施 T2-RDY-001 内部发布准备整改。

## 准入范围

- 独立 ADR、发布物目录、合成签名与验签、SBOM/许可证、部署预检、运维证据和 Go/No-Go；
- 自有设备适配包许可证与变更日志占位修复；
- 固定故障向量、完整既有回归、不可变证据索引和周门禁报告；
- 对既定四项 `G8C-REL-*` 发现形成内部处置，但不伪造外部关闭。

## 禁止范围

新业务、数据库迁移、依赖升级、真实支付、真实设备/外设、伙伴现场、生产签名/KMS、未经授权云写入、完整 Alpha、生产部署、商业 tag、商业声明与 SLA 均为 0。

## 状态

T2-PERF-002 更新为 `ACCEPTED`；T2-RDY-001 完成本文与 ADR-066、契约、测试和回退准入后进入 `IN_PROGRESS`。T2-LIC-001、PAY/HWD/PRN/PAR、UAT/REL、JSH 状态不变。
