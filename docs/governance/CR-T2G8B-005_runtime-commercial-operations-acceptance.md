# CR-T2G8B-005：商业 SaaS 运营正式运行时汇总验收

## 决策

`CONDITIONAL GO / IN_PROGRESS`。新增唯一汇总 Requirement `T2-E2E-005`，不新增商业业务能力。

## 价值与范围

把已接受的 SAA、SUB、SVC 从 Controller 合成契约提升为正式 MySQL/Redis/认证/权限/持久化装配验证，提前发现只有真实运行栈才会暴露的 P0/P1 缺陷。

允许修改范围仅包括：正式 API 旅程、CI、证据与治理，以及被旅程证明为 P0/P1 的装配、授权、幂等、历史保留或可观测性修复。本次确认的 P0 是平台独立审批授权死锁，处理方式见 ADR-061。

## 非目标

- 不接入真实计费、通知、支付 Provider、设备、外设或伙伴现场。
- 不新增业务表、领域状态机、Controller、Vue/Flutter 页面或后台任务。
- 不改变 PAY/HWD/PRN/PAR、UAT/REL、LIC/JSH 状态。
- 不形成 FULL_ALPHA、PRODUCTION、COMMERCIAL 或商业 SLA 结论。

## 验收

- 经公开 HTTP API 完成平台职责分离、商户开户、套餐与权益、租户激活、订阅续期/降级/恢复、实施项目和服务工单、租户停用/恢复。
- MySQL/Redis 正式运行；业务旅程直接数据库写入和 Redis 操作为 0。
- 至少验证两类同幂等键异内容拒绝、独立审批、独立工单关闭、历史保留和租户范围。
- P0/P1 缺陷为 0；完整 CI、SBOM、许可证、安全和证据门禁通过。
