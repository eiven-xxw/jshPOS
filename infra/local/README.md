# 本地开发运行环境

本目录提供商业 V1 内部代码的单机调试入口。默认启动 MySQL 8.4.11、Redis 7.4.10、
商业 `ruoyi-admin.jar` 和 Vue 管理后台；数据、日志和随机 Secret 均保存在 Git 忽略目录。

## 一键启动

前置条件：Windows PowerShell 7、JDK 21、Node 24、pnpm 10.33 和已启动的 Docker Desktop。

```powershell
pwsh ./scripts/local/Start-Local.ps1
```

首次运行会：

1. 生成 `infra/local/.env.runtime.local` 随机本机 Secret；
2. 启动 MySQL/Redis 并仅在缺少 `sys_user` 时导入 RuoYi 基础表；
3. 用 `local` profile 构建并启动商业 JAR，Flyway 自动执行 V1—V90；
4. 校验当前迁移为 V90、业务数据库物理外键数为 0；
5. 安装锁定的 Web 依赖并在 `http://127.0.0.1:4173` 启动管理后台。

服务地址：

- 管理后台：`http://127.0.0.1:4173`
- 服务端：`http://127.0.0.1:8080`
- Swagger：`http://127.0.0.1:8080/swagger-ui/index.html`
- MySQL：`127.0.0.1:3306/jshpos`
- Redis：`127.0.0.1:6379`

RuoYi 基础 SQL 保留上游本地管理员种子，仅用于本机调试。首次登录后应立即修改口令，
不得把该账号、数据库卷或 `.env.runtime.local` 复制到共享、伙伴、测试或生产环境。

## 验证与停止

```powershell
pwsh ./scripts/local/Test-Local.ps1
pwsh ./scripts/local/Stop-Local.ps1
```

停止命令不会删除 MySQL/Redis 数据卷或本地 Secret。需要重新生成 Secret 时，必须先停止
本地服务，再手工移走 `infra/local/.env.runtime.local`；不要在已有加密数据上直接轮换密钥。

## 数据库初始化

推荐使用：

```powershell
pwsh ./scripts/local/Initialize-LocalDatabase.ps1
```

如使用主机 MySQL 客户端，可从仓库根目录执行
`infra/local/mysql/jshpos-local-init.sql` 导入 RuoYi 基础表，随后以 `local` profile 启动
服务端，让 Flyway 创建全部业务表。禁止直接把历史迁移拼接后绕过 Flyway 历史表。

最终 Schema 规则：

- V1—V89 保持不可变，V90 前向删除 308 个业务外键；
- 最终 `information_schema.referential_constraints` 必须为 0；
- 主键、唯一键、CHECK、NOT NULL、不可变触发器和索引继续保留；
- 引用完整性由 Owner 应用端口、可信租户校验、幂等与一致性审计负责。

## Flutter POS

```powershell
cd pos-flutter
flutter pub get --enforce-lockfile
flutter run
```

未提供认证终端材料时，POS 会正常启动但保持 `HWD_SECURE_CREDENTIAL_UNAVAILABLE` 的
失败关闭状态。这是预期行为；本分支不会用本地 Fake 冒充真实设备激活。完整三业态业务链路
可继续使用仓库既有正式栈 E2E 测试调试，真实终端对接仍需独立解阻。

## IntelliJ IDEA 调试

1. 用 Maven `local` profile 构建 `server`；
2. 运行 `New-LocalEnvironment.ps1`，把生成文件中的键值配置为运行配置环境变量；
3. 再执行 `Set-JshPosApplicationEnvironment` 等价路径设置，或先运行 `Start-Local.ps1 -SkipWeb`；
4. 以 `local` Spring profile 调试 `RuoYiApplication`；
5. Web 单独执行 `pnpm dev`。

本地结果只能作为 `LOCAL_DEVELOPMENT` 证据，不代表支付沙箱、真实设备、完整 Alpha、生产
容量、商业验收或 SLA。
