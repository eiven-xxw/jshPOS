# 页面、API、Owner、数据与测试覆盖矩阵

## 1. 机器矩阵

CI 由 `scripts/audit_t2_gate9a_product_completeness.py` 生成：

- `requirement-coverage.csv`：87 行原子需求链；
- `owner-module-matrix.csv`：22 行 Owner 装配与源码/测试统计；
- `page-api-matrix.csv`：26 行 Vue/Flutter 正式业务页面；
- `api-drift.json`：300 项 Controller 与 257 项 OpenAPI 的双向差异；
- `client-api-roots.json`：页面 API 模块声明的 40 个端点根与服务端 Controller 前缀映射；
- `production-markers.json`：生产标记分类；
- `surface-review-gaps.json`：需逐页复核的页面证据。

## 2. 页面汇总

| 页面族 | 数量 | 正式 API/应用端口 | 当前判定 |
|---|---:|---|---|
| 商品、价签、基础组织 | 3 | Catalog、Foundation | 已装配；部分错误恢复证据待补 |
| 库存、采购、成本、促销、会员、发布 | 6 | Operations 聚合 API 与各 Owner | 已装配；部分单飞/错误恢复证据待补 |
| 迁移、开店、批次、日结、异常 | 5 | Migration、Onboarding、Inventory、Operations | 已装配；异常/批次直接页面测试待补 |
| 报表、终端 | 2 | Reporting、Sync/Release | 已装配；页面失败恢复/直接测试待补 |
| SaaS、订阅、服务 | 3 | SaaS、Subscription、Service | 已装配；页面失败恢复证据待补 |
| Flutter 登录销售、班次、退货、换货、组合支付 | 6 | 正式应用服务、Repository、设备失败关闭端口 | 已装配；3 个页面需补直接恢复/单飞证据 |

所有 Flutter 正式页面的静态扫描均未发现直接 `MethodChannel`、SQLite raw API 或 Mapper 访问；
Vue 正式页面未发现直接数据库访问。页面矩阵中的 `REVIEW` 表示证据不足，需要在 G9A-R3 逐页
确认，不能直接当作已证明的功能故障，也不能静默当作通过。

## 3. API 结论

- 服务端存在 300 项正式 Controller 操作；
- 14 个页面 API 模块声明的 40 个端点根均存在正式服务端 Controller 前缀，未发现页面 API 根完全没有服务端实现；
- 当前被选为权威的 OpenAPI 有 257 项操作；
- 64 项 Controller 操作未被当前 OpenAPI 精确覆盖；
- 21 项 OpenAPI 操作没有精确匹配 Controller；
- 未观察到新的无解释权限缺口、重复 operationId 或客户端 DTO `tenant_id` 授权入口。

差异主要集中在 Gate 7/8 后新增的秤码、补货、会员权益、异常中心、SaaS、Subscription 和
Service 契约；第一修复批必须逐操作确认权威路径，不能简单删除契约或为凑数字改 Controller。
