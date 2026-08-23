# T2-MEM-003 独立 CR 与正式开发准备索引

当前结论：`CONDITIONAL GO RECOMMENDED / AWAITING SPONSOR`。

`T2-MEM-003` 继续保持 `DRAFT / CR_REQUIRED`。本目录只有静态设计、DRAFT 契约、合成向量和
验收准备；没有新增运行时代码、数据库迁移、Controller、Flutter/Vue 业务页面或后台任务。

## 文档

1. [01_CR与商业价值范围影响分析.md](01_CR与商业价值范围影响分析.md)
2. [02_数据主权状态机计算顺序与不变量.md](02_数据主权状态机计算顺序与不变量.md)
3. [03_隐私离线多门店兼容与回退.md](03_隐私离线多门店兼容与回退.md)
4. [04_API事件迁移与跨端契约准备.md](04_API事件迁移与跨端契约准备.md)
5. [05_测试矩阵CI与量化验收.md](05_测试矩阵CI与量化验收.md)
6. [06_T2_MEM003独立CR与正式开发启动评审报告.md](06_T2_MEM003独立CR与正式开发启动评审报告.md)
7. [07_T2_MEM003正式开发下一步操作指令.md](07_T2_MEM003正式开发下一步操作指令.md)

## 机器可校验契约

- `contracts/t2/gate7d-mem003-prep/mem003-prep-admission.json`
- `contracts/t2/gate7d-mem003-prep/calculation-order-v1.json`
- `contracts/t2/gate7d-mem003-prep/openapi-member-benefit-price-draft.yaml`
- `contracts/t2/gate7d-mem003-prep/member-benefit-events-draft.schema.json`
- `contracts/t2/gate7d-mem003-prep/member-benefit-price-vectors.json`
- `contracts/t2/gate7d-mem003-prep/persistence-design-registry.csv`

准备阶段基线：`39a72c65a08899f305ee0c04a5e337e1ee9ffbc9`。
