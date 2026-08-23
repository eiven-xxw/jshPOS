# 覆盖、失败 seed 与性能边界

## 覆盖矩阵

| Requirement | API | 应用/Owner | MySQL | Redis/认证 | 关键验收 |
|---|---|---|---|---|---|
| T2-SAA-001 | `/api/v1/saas/**` | SaaS + Foundation | V81/V82 | 登录会话、平台角色 | 独立审批、权益发布、租户生命周期、幂等冲突 |
| T2-SUB-001 | `/api/v1/subscriptions/**` | Subscription + SaaS 只读端口 | V83/V84 | 登录会话 | 激活、续期、降级、恢复、幂等冲突 |
| T2-SVC-001 | `/api/v1/service/**` | Service + Subscription/SaaS 只读端口 | V85/V86 | 登录会话、租户权限 | 目录、实施、工单租约、独立关闭、幂等冲突 |

固定 seed 位于 `contracts/t2/gate8b/failure-seeds-v1.json`。运行时证据记录每次 API 的路径、业务码和耗时，但不保存请求正文或认证资料。

性能只记录合成旅程总耗时、观察数和单次 API 最大耗时，用于同配置回归发现退化；不设商业 SLA，也不外推真实商户并发、网络或生产容量。

## S25 收口结果

- 8 个计划固定 seed 全部通过；另有 6 个 P0、6 个 P1 运行时缺陷被固定为治理、单元、Schema 或正式旅程回归；
- 正式旅程 55 次 HTTP 观察全部通过，总耗时 5355ms，单 API 最大 1286ms；
- 同键异内容分别在 SaaS、Subscription、Service 返回受控冲突；独立审批、独立关闭和租户停用/恢复历史均通过；
- P0/P1 开放数为 0；外部执行、直接业务数据库写入和 Provider 网络调用为 0。

以上来自 GitHub Actions Run `32670082176`，仅为干净执行器内部趋势。后续环境、数据量或并发模型变化时必须重新建立基线，不得据此承诺生产容量或商业 SLA。
