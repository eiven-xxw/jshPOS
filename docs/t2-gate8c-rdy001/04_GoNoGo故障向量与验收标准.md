# Go/No-Go、故障向量与验收标准

## 内部 GO 条件

全部必需制品唯一、SHA-256 正确、临时签名验真通过、私钥不存在、组件 SBOM/许可证完整、部署预检通过、迁移/恢复/回退通过、内部 P0/P1 为 0、外部执行为 0，方可输出 `GO_INTERNAL_RELEASE_READINESS`。

## 强制 NO-GO

- Full Alpha：PAY/HWD/PRN/PAR 未解阻，`NO_GO_BLOCKED_EXTERNAL_P0`；
- Production：UAT/REL、真实签名/KMS/PITR/灾备未完成，`NO_GO`；
- Commercial：T2-LIC-001 0/3 且外部 P0 未关闭，`NO_GO`。

## 故障矩阵

固定 14 个 seed 覆盖缺件、重复、摘要、签名、私钥、commit/Run、供应链、许可占位、默认 Secret、Debug APK 误标生产、外部阻断漂移、UAT/REL 漂移、恢复/回退缺失和外部执行非零。任何 seed 未被拒绝都阻断 T2-RDY-001。

## 验收

同一提交必须完成治理双平台、Server、Web、Flutter 双平台/Android、MySQL、SQLite、安全、SBOM、许可证、部署预检、恢复/回退、制品装配、签名验真和证据聚合。不得自动重跑失败 Job，不得降低阈值或创建绿色占位。
