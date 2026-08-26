# ADR-074：Gate 10A-R2 Server、数据库与资源整改边界

- 状态：Proposed
- 日期：2026-08-26
- 决策范围：Gate 10A-R2 准备与待批正式整改

## 背景

Gate 10A-R1 已关闭 CI、四栈依赖快照与供应链治理问题。当前 R2 只处理三个既有 P2：
大型 Owner 生产类缺少复杂度预算、关键 SQL 缺少 MySQL 查询计划/N+1 回归、Server 长期
资源斜率证据不足。重构和性能调整若同时铺开，会使事务、Owner 与数据守恒证据失去可比性。

## 建议决策

1. 严格按 `MTN → SQL → RES` 串行；前项未独立 VERIFIED 并经确认，后项不得改运行时；
2. MTN 先建立行为金标、公开 API、错误码、事务回滚和 Owner 边界保护，再拆分职责；禁止按行数
   机械拆类或把领域逻辑移动到 Controller、Mapper、通用工具类；
3. SQL 先冻结 MySQL 8.4 数据分布、查询参数、执行计划、查询数和分页预算，再做最小优化；若需要
   新索引或数据库对象，必须停止并提交独立 CR，且只能新增前向迁移；
4. RES 先执行 10 分钟冒烟，再执行 24 小时内部合成长稳；短窗口、单点峰值和本地容器结果不得
   冒充生产容量或商业 SLA；
5. 任何调整不得改变资金、库存、租户、支付、同步、幂等、事件 Schema、API 业务语义和历史事实；
6. 三项 Finding 分别独立 VERIFIED，项目发起人确认前保持开放，R3 不得自动启动。

## 回退与停止线

- 每次运行时整改保持单一职责、独立提交和原行为测试；失败时回退该整改提交，不回退或改写事实；
- 已发布 MySQL/SQLite 迁移不得修改；新索引、Schema 或语义变化触发独立 CR；
- 出现 P0/P1、数据守恒变化、租户越权或资源持续正斜率时立即 `NO-GO`。

## 证据边界

最高结论仅为 `INTERNAL_SERVER_DATABASE_MAINTAINABILITY_PREPARED`。不代表 SANDBOX、
REAL_DEVICE、REAL_PERIPHERAL、PILOT、FULL_ALPHA、PRODUCTION、COMMERCIAL 或商业 SLA。
