# 权限、审计、API/事件与 Owner 端口

## 权限

- `member:profile:create/read/update`：档案创建、掩码查询和最小修改；
- `member:identity:bind/revoke/merge`：身份绑定、撤销和需审核的合并/拆分；
- `member:consent:record`：同意与撤回；
- `member:privacy:request/process/export`：隐私工单创建、处理和受控导出；
- `member:points:post/adjust/read`：MEM-002 准入后启用，人工调整必须独立审批。

所有查询、导出、隐私处理和后台任务同时执行可信 tenant、组织/门店范围和服务端权限。

## 审计

审计保存 tenant、actor、action、resource ID、command/event/idempotency、reason/approval、correlation、occurred/received、result、密钥版本和 before/after 摘要。不保存原始身份值。

## 契约

- OpenAPI：`contracts/t2/gate5c/openapi-member-v1.yaml`；Controller 只做 DTO 校验与转换。
- 事件：`member.profile.changed.v1`、`member.consent.changed.v1`、`member.points.posted.v1`、`member.level.changed.v1`。
- 入站：`order.completed.v2`、`return.completed.v1`通过 Member Inbox 幂等消费；不直接读写 Order/Return 表。
- 事件对 Reporting 默认仅含 member_id、等级代码、数量、业务日和摘要，不含 PII。
