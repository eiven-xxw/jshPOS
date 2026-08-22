# ADR-046：Gate 7C 货架价签任务与失败关闭打印边界

- 状态：Accepted
- 日期：2026-08-22
- Requirement：T2-LBL-001

## 决策

在 `jshpos-catalog` 内建立独立 ShelfLabel Owner。Pricing/Catalog Owner 继续独占价格簿、价格项、商品、条码和单位；
ShelfLabel Owner 只能通过明确只读端口消费已发布快照，并独占版本化价签模板、按门店换签任务、任务项、异常和只追加事件。
价格发布事务通过进程内端口生成稳定来源键的换签任务；停用价格版本生成回退任务。重复同摘要返回原任务，同来源键异摘要失败关闭。

任务按门店拆分，价格项按 SKU/单位拆分。未来价格在生效前生成待换签任务；迟到和乱序使用
`effective_at → scope_priority → price_version → source_price_book_id` 的确定性顺序收敛，较旧未完成任务项标记为 `SUPERSEDED`，
已确认历史不回写。任务状态只表达软件工作流，不表达真实打印结果。

价签模板使用版本化纯文本占位符，只允许 `productName`、`skuCode`、`barcode`、`unitName`、`oldPrice`、
`newPrice`、`storeName`、`priceVersion`、`effectiveAt`、`taskStatus`、`exceptionReason`。禁止 HTML/脚本、路径、公式、
未知占位符、控制字符和超长内容；Vue 只按文本展示服务端渲染结果。

## 数据与实现边界

- 模板：`CRUD_ENTITY + HYBRID`，发布后不可变，纠正创建新版本或停用。
- 任务：`CONTROLLED_WRITE + HYBRID`，只允许具名状态迁移和乐观锁。
- 任务项、异常、事件：业务事实只追加；换签确认或异常通过新事件并受控更新当前投影，不改历史事件。
- 简单单表命令使用 MyBatis-Plus；跨价格、商品、条码、单位、门店和任务聚合的查询使用 Mapper XML。
- MySQL 迁移只前进；回退通过停用入口、重新处理原来源键和安全前向修复，不删除任务历史。

## 外部边界

`T2-PRN-001` 继续 `BLOCKED`。本 Gate 仅提供失败关闭 `ShelfLabelPrintPort`，默认实现始终返回 `PRINTER_UNAVAILABLE`，
禁止引入打印 SDK、USB、串口、蓝牙、真实打印或外设命令。软件预览、Fake 和人工换签确认均不得宣称打印成功或 `REAL_DEVICE` 通过。
