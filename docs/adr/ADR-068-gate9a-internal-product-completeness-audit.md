# ADR-068：Gate 9A 商业 V1 内部产品完整性审计

- 状态：Accepted
- 日期：2026-08-24
- 关联：T2-CMP-001、T2-CORE-001、T2-API-001、T2-INT-001、T2-E2E-004、T2-E2E-005
- 工作基线：`4ba20f8bb9bfdc36f4fee1b831ca35c7b54b9533`

## 背景

现有 87 项 T2 需求已经由项目发起人接受为不同等级的内部软件证据，但“需求已接受”不自动
等于所有页面、API、运行时装配和当前三业态旅程已经形成一条完整产品链。Gate 6G 完整性工具
形成于 15 个 Owner 的历史快照；Gate 7 和 Gate 8 后续新增 Migration、Onboarding、Operations、
SaaS、Subscription、Service 等模块后，该工具仍只统计 15 个模块，并会把 Windows 不支持 POSIX
权限的受控兼容分支误判为临时实现。

## 决策

1. 新建 Gate 9A 当前态审计，不修改任何历史审计脚本、报告或制品；当前态审计固定检查 87 项
   已接受需求和 22 个正式 Owner 模块。
2. 需求覆盖链必须从 Requirement 一直追踪到页面、API、Application Service、Domain、持久化、
   数据迁移、事件、权限审计、测试和 CI；后端或质量型需求允许使用明确的 `N/A_BY_SCOPE`，但
   必须说明理由，禁止空白或绿色占位。
3. 生产标记采用逐条分类：测试专用构造器、支付/硬件失败关闭、操作系统兼容分支可登记为受控
   例外；未分类的 Fake/Mock/Stub/Locked/InMemory/TODO/空实现/演示代码一律形成发现并失败关闭。
4. 审计完成与缺陷关闭是两个门禁。审计可以在 P0/P1 已如实登记、范围完整且证据可重现时形成
   `CONDITIONAL PASS`；运行时 P0/P1 必须在后续独立修复批次逐项关闭，不得在准备期静默修改。
5. 第一修复优先级按“正式 API/OpenAPI 契约漂移 → 生产演示面和页面恢复能力 → 当前 22 Owner
   三业态正式栈 E2E”串行，任何新增业务需求必须另行 CR。
6. 外部 PAY/HWD/PRN/PAR、许可证、完整 Alpha 和生产发布的原状态及证据等级保持不变。

## 后果与验证

- 新增 22 Owner 机器清单、87 项覆盖矩阵、页面/API 质量矩阵和结构化缺陷账；
- Ubuntu/Windows 必须得到相同的归一化审计结论；
- 当前分支不得出现 `server/`、`admin-web/`、`pos-flutter/`、`packages/`、`infra/` 或已发布迁移
  的运行时变更；
- 完成后只提交第一批修复启动指令，等待项目发起人确认，不自动编码。
