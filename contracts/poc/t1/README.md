# T1 Week 1 风险 PoC 契约

本目录只定义 T1 Week 1 的合成输入和证据格式，不是商业 API，也不承诺任何支付机构、Android 设备或外设已接入。

| 契约 | 用途 | 允许证据等级 |
|---|---|---|
| `provider-profile.schema.json` | 十家支付候选的公开资料能力档案 | `STATIC` |
| `payment-operation.schema.json` | 统一支付创建/查询/退款/通知合成操作封套 | `FAKE` |
| `device-operation.schema.json` | 统一设备能力、超时、幂等、错误与恢复结果 | `FAKE` |
| `fault-script.schema.json` | 设备、支付、离线、同步与租户故障编排 | `FAKE` |
| `sync-event.schema.json` | 与商业订单无关的合成同步事件 | `FAKE` |
| `data-package.schema.json` | 合成商品/价格包的验签与切换输入 | `FAKE` |
| `upgrade-case.schema.json` | 虚构 App/Schema 兼容和回退矩阵 | `FAKE` |
| `evidence.schema.json` | CI 生成的机器可读证据封套 | `STATIC`、`FAKE` |

`SANDBOX`、`REAL_DEVICE`、`PILOT` 证据不在本周生成；缺少外部资料时必须保留 `BLOCKED`。
