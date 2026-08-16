# T1 Week 3 合成交叉故障契约

本目录只定义 Week 3 隔离 `STATIC/FAKE` 探针输入和证据结构。它不是正式同步、支付、升级或数据包协议，不得被生产模块依赖。

- `cross-fault-plan.schema.json`：约束四类交叉故障夹具的共同元数据。
- `failed-seed-ledger.schema.json`：要求观察到的失败 seed 可追踪、修复后固定回归；空集合必须如实保留。
- `evidence.schema.json`：将证据限制为 `T1-WEEK3`、内部合成范围及 `STATIC/FAKE` 等级。

`SANDBOX`、`REAL_DEVICE`、物理断电、真实网络和商业验收不在本目录范围内。
