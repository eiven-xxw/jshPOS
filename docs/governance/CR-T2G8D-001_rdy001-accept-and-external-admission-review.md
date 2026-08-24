# CR-T2G8D-001：RDY-001 接受与外部执行准入复核

- 状态：APPROVED_PREP_ONLY
- 日期：2026-08-24
- 发起人：项目发起人
- 基线：`bd1dee42bacfb75874d601e16828c8f82720986b`
- 分支：`t2/gate8d-prep-external-p0-license-alpha-admission`

## 已批准变更

1. 将 `T2-RDY-001` 由 `VERIFIED` 更新为 `ACCEPTED`，保留内部候选证据上限；
2. 离线复核 PAY/HWD/PRN/PAR 四条受控材料和逐轨执行准入条件；
3. 复核 Aviator、simple-http、MySQL Connector/J 的替换或法务关闭证据；
4. 冻结完整 Alpha UAT 与发布的前置条件、RACI、证据等级和 Go/No-Go；
5. 建立机器可校验的状态守恒、零执行、Secret/PII、依赖差异与证据门禁。

## 明确非目标

不修改业务/基础设施运行时、数据库迁移、依赖或外部适配器；不访问 Provider，不执行真实资金、
设备/外设命令或伙伴现场，不启动完整 Alpha 或生产发布。缺少真实材料不得形成绿色占位。

## 当前事实与决定

- 支付 `0/11`、硬件 `0/2`、外设 `0/6`、伙伴 `0/5` 且书面意愿 `0/3`；四轨均为
  `NOT_ACHIEVED/BLOCKED/NO_GO`；
- 许可证 `CLOSED 0/3`；Aviator 仍在依赖管理声明中，MySQL Connector/J 仍为运行时依赖，
  simple-http 缺少最终制品排除与书面关闭证据，因此三项均 `OPEN`；
- UAT/REL 继续 `DRAFT/NO_GO`；所有外部执行计数为 0。

## 验收

五条独立报告、Gate 8D-Prep 评审报告、RTM、ADR、合同和索引一致；Ubuntu/Windows、范围、
Secret/PII、依赖差异和证据聚合门禁通过。完成后仍需项目发起人确认，不自动进入外部执行。
