# Reporting 权限、审计、API/事件与安全导出

## 1. 权限矩阵

| 权限 | 能力 | 额外约束 |
|---|---|---|
| `report:operation:read` | 查询销售、收银、库存和成本投影 | 服务端可信租户与门店范围 |
| `report:projection:ingest` | 受控内部执行器消费来源事件 | 不开放给普通终端；请求 tenant 被拒绝 |
| `report:projection:rebuild` | 建立影子版本并重建 | 租户管理员；完整审计 |
| `report:export:request` | 申请受限导出 | 日期、门店、字段与预计行数上限 |
| `report:export:approve` | 独立审批高风险导出 | 审批人不得等于申请人 |
| `report:export:generate` | 受控任务生成 | 可信任务上下文与租户命名空间 |
| `report:export:download` | 获取短期单次下载 | 绑定用户、租户、制品、过期时间 |
| `report:repair:manage` | 处理差异/修复任务 | 只能更改 Reporting 修复状态 |

路由权限只控制展示，应用服务仍调用 `TrustedTenantContext` 和 `ScopeAuthorizationService`。查询多个门店时逐项校验；空门店集合不代表全租户。

## 2. API

- `POST /api/v1/reporting/source-events`：受控事件消费；不接受 tenantId。
- `GET /api/v1/reports/sales-daily`：日期、门店及可选终端/收银员查询。
- `GET /api/v1/reports/inventory-cost-daily`：日期、门店及可选仓库/SKU查询。
- `POST /api/v1/reporting/rebuilds`：创建并执行受控重建。
- `POST /api/v1/report-exports`、`/{id}/approve`、`/{id}/generate`、`/{id}/download-token`、`/{id}/expire`。
- `GET /api/v1/reporting/differences` 与 `POST /{id}/transitions`。

所有写接口使用稳定幂等键、请求摘要和关联标识；错误码统一前缀 `RPT-G5D-*`。

## 3. 审计

必须记录来源冲突、缺口、晚到补算、重建开始/完成/失败/切换、查询越权、导出申请/审批/拒绝/生成/下载/过期、差异状态迁移。审计只保存 ID、枚举、范围、数量、摘要和结果，不保存原始业务明细或 PII。

## 4. 安全导出

1. 范围：最多 31 个业务日；同步普通导出最多 100,000 行，超过 10,000 行或含成本字段必须独立审批；百万级仅用于合成性能基线和异步分片，不提供单文件下载。
2. 字段：由服务端报表类型白名单决定，客户端不能指定 SQL、表达式、表名或任意列。
3. CSV：UTF-8；所有以 `= + - @ \t \r` 开头的文本前置单引号；CR/LF 归一化；RFC 4180 转义；首部写入租户、范围、生成时间和制品摘要水印。
4. 路径：对象键固定为 `reporting/{tenantId}/{exportId}/{artifactSha256}.csv`；拒绝 `..`、反斜杠、绝对路径和客户端对象键。
5. 下载：只返回随机高熵令牌的摘要；令牌绑定租户、申请人/获准下载人、制品和 10 分钟有效期，首次成功消费后失效。
6. 清理：临时文件写入受控 Runner/应用临时根目录，异常和成功后均删除；过期任务清除对象并追加审计，元数据保留。

## 5. 端口

`ReportProjectionPort` 负责 Inbox、检查点、投影、差异与重建；`ReportArtifactStore` 只接收服务端生成的租户对象键和字节流，不自行决定授权；`ReportDownloadTokenProtector` 生成/校验令牌摘要。任何适配器都不得依赖 RuoYi 系统 Mapper 或其他 Owner Mapper。
