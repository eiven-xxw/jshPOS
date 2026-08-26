# 范围边界与 MTN 关闭复核

## 已确认前置条件

- 起点：`f2a9f454d5c306142b71dbae398853ae17daab9e`；
- `G10A-MTN-P2-001`：`CLOSED_IN_GATE10A_R2_R1`；
- `G10A-SQL-P2-001`：`PREPARED_AWAITING_SPONSOR_RUNTIME_CONFIRMATION`；
- `G10A-RES-P2-001`：`PREPARED`。

## 本阶段允许

查询身份与摘要、MySQL 8.4.11 执行环境、10k/100k/1m 合成分布、计划取证格式、索引/
分页/N+1/超时预算、租户权限攻击、失败 Seed、影响分析、测试矩阵、CI 和启动评审。

## 本阶段禁止

- Server 运行时代码、Mapper Java/XML、注解 SQL、索引和数据库对象；
- 依赖、配置、MySQL/SQLite 已发布迁移；
- API、错误码、资金、库存、租户、支付、同步、事件或 Owner 语义；
- 资源长稳整改、外部执行、完整 Alpha 或生产发布。

审计确认基线后所有运行时目录差异为 0。
