# ADR-047：Gate 7C 确定性补货建议与采购草稿边界

- 状态：Accepted
- 日期：2026-08-22
- 决策范围：T2-RPL-001

## 决策

在 `jshpos-procurement` 模块内建立独立 Replenishment Owner，以 `rpl_*` 表独占规则
版本、生成运行、建议、状态事件、审计和 Outbox。该 Owner 通过
`ReplenishmentInventorySnapshotPort` 读取 Inventory 权威余额/流水检查点，通过
Catalog 只读端口冻结单位换算，通过 Procurement 只读端口读取活动供应商与已批准
在途量；不得直接查询或更新其他 Owner 私有表。

建议计算只使用版本化最低/最高库存、最小订货量、订货倍数和可选已批准在途量。
当 `available + includedTransit < minimum` 时，以补到 `maximum` 为基础，转换为采购单位，
再向上取整到订货倍数且不低于最小订货量。数量固定六位，倍数取整使用 `CEILING`，
禁止浮点数和预测式 AI。

建议只是一项可复核事实，采用 `GENERATED → REVIEWED → APPROVED →
PURCHASE_DRAFTED` 主路径，并支持 `REJECTED/STALE/FAILED`。只有 Procurement Owner
可创建采购草稿；草稿必须绑定建议来源，且不会产生库存、成本或收货效果。

## 后果与回退

- Inventory 仅新增不含 tenant_id 的只读端口，不改变库存账本或余额算法。
- Procurement 通过前向迁移记录采购单来源；既有手工采购单保持 `MANUAL`。
- 回退只能停用新入口、停用规则版本或将开放建议标记过期；不得删除建议、回写库存
  流水或修改已生成采购草稿。
- DMT、ONB、LOT、真实硬件、支付和外部连接器不因本决策获得准入。
