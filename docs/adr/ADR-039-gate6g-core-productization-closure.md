# ADR-039：Gate 6G 商业 V1 内部核心代码收口

## 状态

Accepted（2026-08-21，项目发起人按 `CONDITIONAL GO` 批准）

## 上下文

Gate 0—6E 已实现并接受 55 项 T2 内部能力，但外部支付、真实设备和伙伴资料未齐。继续等待外部材料会延迟内部代码成熟度；直接新增功能又会扩大范围，并可能掩盖正式装配、API、持久化和集成缺口。

## 决策

1. Gate 6G 只收口已接受商业 V1 能力，不引入新的领域算法或外部适配器。
2. 串行顺序固定为核心生产代码、正式 API、数据环境、Owner 集成、内部 V1 核心候选。每项独立 `VERIFIED` 后才允许下一项进入实现。
3. 生产路径不得依赖测试 Fake、Mock、Stub、InMemory、静态合成结果或静默成功的 Locked 适配器。尚未解阻的外部能力必须显式 `BLOCKED/UNAVAILABLE` 并失败关闭。
4. Owner 只写自己的事实；正式集成通过应用端口、版本化事件和 Inbox/Outbox，禁止 Controller 领域逻辑、跨 Owner Mapper 与前端重算。
5. 已发布迁移只前进。空环境初始化、合成种子、投影重建和清理只能使用虚构数据，不形成生产或伙伴证据。
6. `INTERNAL_V1_CORE_CANDIDATE` 是内部工程证据上限，不等于 `FULL_ALPHA/PILOT/PRODUCTION/COMMERCIAL`，也不改变外部阻断和 V1 汇总验收状态。

## 后果

- 可以在不等待外部资料的情况下发现并关闭生产装配、契约、数据和跨模块缺陷。
- Provider 与硬件以后只需实现稳定适配器端口，不能反向污染核心状态机。
- 内部代码收口完成后仍必须恢复支付沙箱、实机和伙伴验收，商业发布门禁不降低。

## 验证

- 机器门禁检查串行 RTM 状态、生产路径占位、Owner 边界、迁移不可变、外部零执行和证据等级。
- 每个 Requirement 独立 CI；最终在干净 Ubuntu/Windows 执行器组合服务端、Web、Flutter、Android、MySQL、SQLite、安全、SBOM、许可证和内部 E2E。
