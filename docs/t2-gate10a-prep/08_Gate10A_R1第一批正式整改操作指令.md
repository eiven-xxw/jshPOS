# Gate 10A-R1 第一批正式整改操作指令（待确认稿）

项目发起人确认后，建议使用以下指令：

> 我确认《T2 Gate 10A-Prep 启动评审报告》，接受准备阶段 CONDITIONAL PASS。按 CONDITIONAL GO 启动 T2 Gate 10A-R1：CI、依赖与供应链治理。本批仅允许关闭 G10A-CI-P2-001、G10A-DEP-P2-001、G10A-SUP-P2-001。先将 ADR-073 更新为 Accepted，冻结当前 Action/二进制/四栈依赖/SBOM/许可证/漏洞与兼容快照，再按“Action Node24 兼容 → Maven → pnpm → Flutter Pub → Kotlin/Gradle → 完整 CI”严格串行。每个生态先补失败回归和回退方案，使用精确版本和 SHA；禁止一次刷新全部锁文件、降低安全/许可证阈值、修改已发布迁移或新增业务。若升级需要改变资金、库存、租户、支付、同步或迁移语义，立即停止并提交独立 CR 与 Requirement ID。完成后提交《Gate 10A-R1 独立周门禁报告》等待确认，不得自动进入 R2。

R1 必须保留历史工作流证据语义；应通过受控版本账和活动/历史分类治理重复引用，不得为减少数量删除失败 Run 或封板证据。
