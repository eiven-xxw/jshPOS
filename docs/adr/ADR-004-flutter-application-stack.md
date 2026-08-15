# ADR-004：Flutter 应用栈

- 状态：Accepted
- 日期：2026-08-15

T0固定Riverpod作为唯一主状态管理、go_router作为路由、Dio作为网络适配入口、Drift/SQLite作为本地持久化方向、不可变模型与代码生成作为模型方向。T0只建立接口与目录，具体依赖在T1评审许可证和替代成本后引入；业务层不得直接调用MethodChannel。
