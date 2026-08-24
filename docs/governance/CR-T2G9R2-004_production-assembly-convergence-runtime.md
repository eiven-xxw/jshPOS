# CR-T2G9R2-004：G9A-R2 生产装配与演示面收敛正式整改

- 状态：APPROVED_RUNTIME_REPAIR
- 日期：2026-08-24
- 发起人：项目发起人
- 基线：`b9333b85f1b46ac444b83346a6a3d44204e7d723`
- 分支：`t2/gate9b-sprint27b-g9a-r2-runtime`
- 复用需求：`T2-CORE-001/T2-SEC-002/T2-RDY-001`
- 缺陷：`G9A-ASM-P1-001`

## 已批准范围

1. 将 ADR-070 更新为 Accepted，并以无参数默认构建作为唯一商业装配路径；
2. 删除 `ruoyi-demo` 活动生产源码、服务端依赖、Springdoc 分组、Vue 页面/API 和初始化种子；
3. 将 `ruoyi-generator/ruoyi-workflow/ruoyi-job` 及非 V1 扩展服务隔离到显式非商业 profile，
   从默认 reactor、商业 JAR、Web、菜单和 SBOM 中排除；
4. 同步清理四方言新装初始化源，并提供只前进、摘要校验、失败关闭的既有环境清理方案；
5. 重建 JAR、Web、SBOM，验证 300 项正式 API、22 Owner、启动与既有完整回归不退化。

## 明确非目标

- 不新增 Requirement ID、业务能力、Controller、领域状态机、业务表或后台任务；
- 不修改已发布 Flyway/SQLite 迁移，不改变资金、库存、订单、租户和审计事实；
- 不重新准入代码生成、工作流、任务调度、演示能力或商业许可证事项；
- 不执行 Provider 网络、真实资金、设备/外设、伙伴现场、完整 Alpha 或生产发布。

## 验收

- 商业默认 reactor、JAR、Web、动态路由、四方言初始化和 SBOM 的目标信号归零；
- generator/workflow/job 服务端源码只能通过显式非商业 profile 构建，demo 活动源码删除；
- WarmFlow 与未准入平台入口默认关闭，配置或菜单不能替代服务端隔离；
- Controller/OpenAPI 维持 300/300、差异 0/0，22 Owner 和全部质量门禁不回退；
- 缺陷只形成 `VERIFIED_CLOSURE_CANDIDATE`，由项目发起人确认后才可正式关闭。
