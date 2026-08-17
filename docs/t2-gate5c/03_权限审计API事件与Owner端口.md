# 权限、审计、API/事件与 Owner 端口

## 权限

- `member:profile:create/read/update`：档案创建、掩码查询和最小修改；
- `member:identity:bind/revoke/merge`：身份绑定、撤销和需审核的合并/拆分；
- `member:consent:record`：同意与撤回；
- `member:privacy:request/process/export`：隐私工单创建、处理和受控导出；
- `member:points:read/freeze/settle/adjust/rebuild`：在线积分读取、冻结、原冻结分配结算、受权调整和投影重建；
- `member:level:manage`：只追加等级事实；`adjust/rebuild/level:manage` 必须是租户管理员。

所有查询、导出、隐私处理和后台任务同时执行可信 tenant、组织/门店范围和服务端权限。
积分与等级命令中的 `storeId` 只作为授权目标；服务端调用 Gate 0 Store Owner 校验数据范围，并按受控门店时区/日切计算业务日。客户端不得提交 `tenant_id` 或自行决定业务日。

## 审计

审计保存 tenant、actor、action、resource ID、command/event/idempotency、reason code 或自由文本摘要、correlation、occurred/received、result、密钥版本和 before/after 摘要。不保存原始身份值、自由文本原因或完整密文。

## 契约

- OpenAPI：`contracts/t2/gate5c/openapi-member-v1.yaml`；Controller 只做 DTO 校验与转换。
- 事件：`member.profile.changed.v1`、`member.consent.changed.v1`、`member.points.posted.v1`、`member.level.changed.v1`。
- 入站：`order.completed.v2`、`return.completed.v1`通过 Member Inbox 幂等消费；不直接读写 Order/Return 表。
- 事件对 Reporting 默认仅含 member_id、等级代码、数量、业务日和摘要，不含 PII。
