# CR-T2LOC-001：本地可运行分支与 MySQL 无物理外键治理

- 日期：2026-09-05
- 状态：APPROVED_IN_PROGRESS
- Requirement：`T2-LOC-001`
- ADR：`ADR-075`
- 基线：`558382093368795e738c4a00c5fc0bbb057da0f4`
- 分支：`t2/local-debug-runnable-20260905`

## 变更理由

项目发起人要求提供可直接用于本地启动和调试的独立分支、可重复数据库初始化入口，
并确定服务端数据库表不再使用物理外键。该工作不新增业务功能，但会改变 MySQL 最终
Schema 约束形态，必须通过独立 CR、唯一 Requirement、ADR 和前向迁移治理。

## 允许范围

1. 本机 MySQL/Redis 初始化、服务端/Web/POS 调试说明与可重复启停脚本；
2. 新增 `local` profile，所有本机 Secret 运行时生成并保留在 Git 忽略目录；
3. 新增唯一 V90 前向迁移删除 V1—V89 形成的现存业务外键；
4. 增加静态无外键门禁、MySQL 8.4 最终 Schema 集成测试和本地健康检查；
5. 更新 AGENTS、开发规范、RTM、ADR、目录索引与变更日志。

## 禁止范围

- 修改 V1—V89 或任何已发布 MySQL/SQLite 迁移；
- 删除主键、唯一键、CHECK、NOT NULL、不可变触发器或支撑索引；
- 改变资金、库存、成本、促销、会员、同步、租户或 API/事件语义；
- 将本机默认账号、生成 Secret 或合成数据用于共享、测试伙伴或生产环境；
- 支付 Provider 网络、真实资金、真实设备/外设、伙伴现场、完整 Alpha 或生产发布。

## Go/No-Go

只有以下全部满足才可把 `T2-LOC-001` 更新为 `VERIFIED`：

- 静态清单确认 308/308 外键均在 V90 删除且未来新增外键失败关闭；
- MySQL 8.4 空库与 V89 升级均到 V90，Flyway validate 通过且最终外键数为 0；
- 本地脚本不提交 Secret，重复执行不重复导入基础数据；
- 商业 JAR 健康启动、Vue 可连接正式 API，既有 Server/Web/Flutter 回归不退化；
- 外部 BLOCKED、UAT/REL DRAFT、LIC/JSH DEFERRED 状态保持不变。
