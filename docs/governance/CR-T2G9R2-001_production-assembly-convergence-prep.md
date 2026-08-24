# CR-T2G9R2-001：G9A-R2 生产装配与演示面收敛准备

- 状态：APPROVED_PREP_ONLY
- 日期：2026-08-24
- 发起人：项目发起人
- 基线：`53e540fd14559e7ae0f907b244b0dbac37167cfe`
- 分支：`t2/gate9b-sprint27b-g9a-r2-prep`
- 复用需求：`T2-CORE-001/T2-SEC-002/T2-RDY-001`
- 缺陷：`G9A-ASM-P1-001`

## 已批准范围

1. 对 `ruoyi-demo` 的 Maven、JAR、Springdoc、Vue、路由、初始化 SQL 与 SBOM 进行只读审计；
2. 对 generator、workflow、job 等非 V1 平台能力形成保留、默认关闭或隔离建议；
3. 冻结商业默认装配、四方言初始化、构建、供应链、启动、回归和回退验收矩阵；
4. 记录 G9A-R1 发起人确认，但不改写 Gate 9A 原始缺陷账；
5. 建立跨平台准备阶段 CI 和机器证据，提交启动评审等待确认。

## 明确非目标

- 不改 POM、运行时配置、Controller、Vue/Flutter 页面、初始化 SQL或已发布迁移；
- 不删除、移动或实现任何生产模块，不关闭 `G9A-ASM-P1-001`；
- 不处理 G9A-R3/R4，不新增业务能力或 Requirement ID；
- 不执行 Provider 网络、真实资金、设备/外设、伙伴现场、完整 Alpha 或生产发布。

## 准备阶段验收

- 基线提交是当前分支祖先，运行时和迁移相对基线变更为 0；
- 机器审计在 Ubuntu/Windows 得出一致的装配事实；
- 当前 JAR/Web/SBOM 的演示信号通过重新构建可复现；
- 保留能力、商业默认路径、四方言清理、测试、回退和 Go/No-Go 已有可执行方案；
- 外部状态和零执行边界无漂移。
