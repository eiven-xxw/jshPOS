# 正式 API 旅程与可重复运行手册

## 环境

- MySQL 8.4 空数据库，按顺序导入 `server/script/sql/ry_vue_5.X.sql` 与
  `server/script/sql/ry_workflow.sql`，再由应用 Flyway 前向迁移至当前版本。SaaS 技术租户
  创建会调用 RuoYi 工作流定义同步，因此工作流基础表属于正式运行装配的强制依赖，禁止通过
  关闭工作流同步或绕过正式租户端口规避。
- Redis 7.4 独立实例；服务端、数据库和 Redis 均使用合成凭据。
- 服务端关闭验证码和 API 请求体加密以适配隔离 CI，但不关闭认证、权限、可信租户或应用层授权。
- 旅程脚本：`scripts/run_t2_gate8b_runtime_api_journey.py`。

## 运行

在服务端健康后，以环境变量提供三个合成密码，并执行：

```text
python scripts/run_t2_gate8b_runtime_api_journey.py \
  --base-url http://127.0.0.1:18080 \
  --output artifacts/t2/gate8b/runtime-api/runtime-api-journey.json
```

脚本只使用公开 HTTP API，不连接数据库、Redis 或 Mapper；不输出 Token、密码、请求正文、商户号、终端号或证书。CI 负责隔离环境创建、基础 Schema 初始化、服务启动和制品归档。

## 旅程

1. 平台超管经 RuoYi API 创建最小权限平台复核角色、复核用户和技术租户套餐。
2. 创建套餐与权益版本，分别由提交人和独立复核人推进审批发布。
3. 创建商户申请、预检、独立审批、技术租户开通、初始化和激活。
4. 创建订阅并执行激活、续期、暂停到 `RECOVERY_ONLY`、恢复到 `NORMAL`。
5. 新租户管理员正式登录，创建组织门店和独立工单复核人。
6. 创建服务目录、实施项目、检查项和工单；解决人与关闭人分离。
7. 平台执行逻辑停用和受控恢复，验证生命周期与历史保留。

## 失败处理

任何 HTTP/业务码、状态、不变量或稳定身份不符合预期时立即失败；不得自动重跑掩盖 Flaky。失败 evidence 和 server log 保留，修复后必须由新提交完整复跑。
