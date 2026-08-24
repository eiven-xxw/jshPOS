# ADR-070：G9A-R2 商业默认装配与非 V1 平台能力隔离

- 状态：Proposed
- 日期：2026-08-24
- 关联：T2-CORE-001、T2-SEC-002、T2-RDY-001、G9A-ASM-P1-001
- 工作基线：`53e540fd14559e7ae0f907b244b0dbac37167cfe`

## 背景

当前 `ruoyi-admin` 直接装配 `ruoyi-demo`、`ruoyi-generator`、`ruoyi-workflow` 和
`ruoyi-job`。它们同时进入默认 Maven reactor、Spring Boot JAR 与聚合 SBOM；Vue 的
全量动态视图扫描还会把演示、代码生成和工作流页面编译进生产包。四种数据库初始化脚本
包含演示菜单、角色授权与测试表。仅删除菜单无法关闭服务端路由、依赖和供应链暴露面。

现有 22 个鲸熵汇 Owner 没有对 generator、workflow 或 job 的代码依赖；既有审批、任务、
资金和对账行为由各自 Owner 的状态机、应用端口和 Outbox 承担。`ruoyi-job` 中的支付宝、
微信账单执行器是固定金额的模拟示例，不能作为正式支付或对账能力进入商业装配。

## 建议决策（等待项目发起人确认）

1. 商业装配成为无参数默认路径；只有默认路径产生的 JAR、Web、SQL、SBOM 和发布清单
   可以进入内部发布候选。任何开发工具 profile 必须显式启用且被商业 CI 拒绝。
2. `ruoyi-demo` 从活动生产源码、默认 reactor、`ruoyi-admin`、Springdoc、Vue、初始化数据和
   SBOM 中移除；需要恢复示例时从本基线 Git 历史取得，不在产品仓库维持双轨运行时。
3. `ruoyi-generator`、`ruoyi-workflow`、`ruoyi-job` 的服务端源码可暂留作未来评估，但全部
   排除于商业默认 reactor/JAR/SBOM；其 Vue 页面、菜单和公开路由不进入商业构建。重新启用
   必须有独立 CR、Requirement ID、安全评审和完整回归。
4. `DemoUnitTest` 等仅位于 `src/test` 且不进入制品的测试夹具不按名称误删；SaaS 内部合成
   旅程中的虚构数据也不与 RuoYi 演示模块混为一谈，由后续页面完整性批次独立复核。
5. 四方言初始化源同步移除演示菜单、角色绑定、测试表和非 V1 平台菜单。已发布 Flyway
   迁移保持不可变；既有环境使用新的受审计前向清理，不得修改历史迁移或覆盖业务事实。
6. WarmFlow、代码生成与任务调度没有商业 V1 准入时必须默认关闭；配置、菜单隐藏和前端
   路由都不能替代服务端依赖与端点移除。

## 后果与验证

- 商业 JAR、依赖树、Spring 映射、Web dist、动态路由、初始化 SQL 和 SBOM 均不得出现被
  排除能力；正式 300 项 Controller、22 Owner 与现有业务旅程必须保持不变。
- 删除演示源和商业装配项是可通过 Git 恢复的代码变更；数据库清理只能前向执行并带预检、
  摘要、审计和失败关闭，不能通过恢复演示表或菜单作为业务回退。
- 本 ADR 当前仅为 `Proposed`。项目发起人确认 G9A-R2 正式整改后才可改为 `Accepted`；
  本准备阶段不得据此修改 POM、配置、页面或 SQL。
