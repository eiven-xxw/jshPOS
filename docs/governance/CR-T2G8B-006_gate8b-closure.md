# CR-T2G8B-006：Gate 8B 内部汇总验收收口

## 结论

`VERIFIED / CONDITIONAL PASS AWAITING SPONSOR CONFIRMATION`。本 CR 只关闭 Gate 8B 正式运行时旅程暴露的 P0/P1 装配、授权、契约和可观测性缺陷，不新增商业业务能力。

## 变更范围

- 默认平台租户的具名独立审批角色保持最小授权，商户租户同名角色失败关闭；
- RuoYi 两段与三段超管权限通配契约均被固定测试覆盖；
- 空环境按基础 Schema、工作流 Schema、Flyway 前向迁移顺序装配；
- 审计摘要复用应用 Jackson Java Time 配置，敏感开户字段禁止进入普通日志；
- `jshpos-service` 注册为 Spring Boot 自动配置并显式装配 Owner 组件；
- 正式旅程对齐 RuoYi `clientid`、日期时间、应用编码和租户管理员角色键契约。

## 验证证据

- 候选提交：`379197394a4c0934dac8a6d4ff1e10e87bdadde3`；
- GitHub Actions Run：`32670082176`，10 个 Job 全部成功；
- 正式 MySQL 8.4、Redis 7.4、可执行 JAR 与公开 HTTP API 共 55 次观察全部通过；
- 旅程总耗时 5355ms，单 API 最大 1286ms，仅作为同配置内部趋势；
- P0/P1 开放数为 0；直接业务数据库写入、Provider 网络、真实资金、设备/外设、伙伴、完整 Alpha、生产和商业声明均为 0；
- 最终证据 Artifact `9501237609`，索引 309 个文件，索引摘要 `99ae85e7110feb30673c5d07dc12e47378974452d47d8ea91adbf664e0537aba`。

## 状态边界

`T2-E2E-005` 只更新为 `VERIFIED`，项目发起人确认前不得更新为 `ACCEPTED`。`T2-PAY-002/HWD-001/PRN-001/PAR-001` 保持 `BLOCKED`，`T2-UAT-001/REL-001` 保持 `DRAFT`，`T2-LIC-001/JSH-001` 保持 `DEFERRED`。
