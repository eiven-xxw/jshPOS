# Gate 6B 权限、审计、API、事件与可信端口

## 1. 权限

| 权限 | 能力 | 附加约束 |
|---|---|---|
| `release:create` | 创建发布草稿 | 租户管理员且对所有门店有数据范围 |
| `release:verify` | 触发对象摘要和签名校验 | 请求/响应和日志不保存签名正文 |
| `release:rollout` | 建立、启动、暂停、扩散、完成批次 | 受兼容与健康门禁约束 |
| `release:task:observe` | 记录软件执行结果 | 不是远程命令权限 |
| `release:revoke` | 阻止后续分发 | 不重写已成功历史 |
| `release:read` | 读取去敏摘要 | 不返回签名、对象内部地址或终端凭据 |

## 2. 可信端口

- `ArtifactBinarySource`：按私有对象键读取内容；必须校验租户命名空间、大小和访问身份。
- `PublicKeyRegistry`：按 key version 返回公钥；私钥不进入应用运行时。
- `TrustedTerminalRegistry`：从 `pos_sync_device` 只读 tenant/store/status/version/capability。
- `SafetyProbe`：组合 pending Outbox、UNKNOWN payment/refund、open shift、营业保护、存储和时钟。
- `AuthorizedStores`：确认当前租户管理员对全部目标门店有权限。

上述任一生产端口未配置时默认适配器返回 503，不创建“绿色占位”。

## 3. API 与事件

HTTP 契约见 `contracts/t2/gate6b/openapi-release-v1.yaml`。请求不允许 tenant_id、终端门店、认证状态、能力、Outbox 或资金状态字段。

事件信封见 `release-events-v1.yaml`，每条包含 event ID、tenant、aggregate、from/to、evidence SHA、correlation ID、occurred_at。事件表只追加，后续出站投递状态若需要变化，必须另建 `CONTROLLED_WRITE` 投递表，不得更新事件本体。

## 4. 审计

创建、验签、STAGED、吊销、灰度启动/暂停/扩散/完成、任务创建和每次观察全部写审计。审计只保存内部 actor ID、状态、关联 ID 和去敏摘要，不保存私钥、原始凭据、完整对象内容、支付敏感数据或 PII。
