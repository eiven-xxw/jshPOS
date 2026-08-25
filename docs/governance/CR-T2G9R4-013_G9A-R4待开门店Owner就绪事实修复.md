# CR-T2G9R4-013：G9A-R4 待开门店 Owner 就绪事实修复

## 1. 触发证据

- 候选提交：`30c723a7cd36d8fa4627189164edfdbc277cf0c0`
- GitHub Actions Run：`32897441445`
- 失败 Job：`formal-runtime`（`97964003257`）
- 正式运行栈 Artifact：`t2-g9a-r4-formal-runtime`（`9581910237`），归档 SHA-256 `ff7f5a3fc7e2c497e7e591129fe47d6cffa12ea6ab25bd732dffadc86fa82e64`
- 三条 Flutter 正式旅程再次成功，Outbox 全部 ACK；正式六对象备份与九项恢复均通过，证明 CR-T2G9R4-012 已关闭时间精度故障；随后社区超市待开门店检查未进入 `READY_TO_OPEN`。

## 2. 根因与边界

R4 旅程已为原营业门店发布 Catalog 数据包和 Inventory 库存策略，但开店计划的目标是独立的 `PREPARING` 门店。Onboarding Owner 应用模板只负责 Foundation 配置绑定，按数据主权不能替 Catalog 或 Inventory Owner 伪造目标门店就绪事实。因此目标门店的 `DATA_PACKAGE` 与 `INVENTORY_POLICY` 检查正确失败关闭。

修复复用已接受的 `T2-DPK-001`、`T2-INV-004`、`T2-ONB-001` 与 `T2-INT-001`，只补齐正式 E2E 的操作顺序；不新增业务、Requirement ID、API、表、迁移或跨 Owner 写入。

## 3. 批准的最小修复

- 开店模板经 Onboarding 正式端口应用后，以租户管理员可信上下文调用 Catalog 正式 API，为目标门店发布版本 1 数据包；
- 调用 Inventory 正式 API，为目标门店发布 `DENY` 负库存策略及稳定策略身份；
- 之后再执行开店检查，要求十项内部检查全部通过且四项外部 P0 精确保持 `BLOCKED`；
- 失败信息只输出检查代码与状态，不输出口令、令牌、真实 PII、请求正文或其他敏感值，避免后续失败只剩不可诊断的聚合提示；
- 禁止直接写 MySQL、跨 Owner Mapper、跳过 Onboarding 状态机或把外部阻断伪造成通过。

## 4. 验收与证据边界

- 脚本语法、R4 治理检查和正式 API 范围检查通过；不修改生产 Java/Dart/Vue 运行时；
- 新候选必须从头复跑完整 GitHub CI，三业态、22 Owner、12 组数据守恒和 12 个固定故障 seed 必须重新形成；
- `productionBackupOrKms`、Provider 网络、真实资金、真实设备/外设、伙伴现场、完整 Alpha 和生产执行继续为 0；
- `T2-PAY-002/HWD-001/PRN-001/PAR-001` 继续 `BLOCKED`，`T2-UAT-001/REL-001` 继续 `DRAFT`，`T2-LIC-001/JSH-001` 继续 `DEFERRED`；
- `G9A-E2E-P1-001` 在完整 CI 全绿和项目发起人确认前继续 `OPEN`。
