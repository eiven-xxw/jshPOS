# Gate 1 商品、价格与数据包契约准备（DRAFT）

本目录仅落实项目发起人批准的 Gate 1 契约和测试准备，对应 `T2-PRD-001..004`、`T2-PRC-001..002`、`T2-DPK-001`。全部需求继续保持 `DRAFT`，不得据此创建运行时 Service、Controller、Mapper、表或发布任务。

机器契约位于 `contracts/t2/gate1`，当前证据等级为 `STATIC`：

- `openapi-product-price-draft.yaml`：候选管理 API；所有写命令要求幂等键和乐观版本。
- `schemas/*.schema.json`：商品、导入批次、价格簿与数据包清单 Schema。
- `events/*.schema.json`：候选事件信封；不是已投产 Topic。
- `test-vectors/two-tenant-product-price-v1.json`：纯合成双租户向量。

进入 Gate 1 正式编码前，必须由项目发起人另行确认；确认后还需重新执行 ADR/RTM 准入，冻结 DEC-G1-01 至 DEC-G1-08，方可把逐项需求从 `DRAFT` 改为 `READY/IN_PROGRESS`。
