# T0 模块边界与依赖方向

## 1. 工程边界

```text
admin-web ──HTTP/OpenAPI──> server
pos-flutter ──HTTP/同步协议──> server
pos-flutter ──Dart接口──> pos_device_adapter ──Platform Channel──> Kotlin厂商插件
server/connector-runtime ──标准契约──> 外部连接器
```

T0只建立工程和契约入口。不得为了演示而在任一端创建伪交易规则。

## 2. 服务端目标领域

T2起按需求准入逐步建立：`platform`、`product`、`pricing`、`promotion`、`order`、`payment`、`inventory`、`procurement`、`member`、`shift`、`connector`、`reporting`。这些领域不得反向依赖Controller或RuoYi代码生成模块。

建议模块内部分层：

```text
interfaces -> application -> domain
                   |
                   v
             infrastructure
```

- `domain` 不依赖Spring MVC、数据库Mapper和第三方渠道SDK。
- `application` 编排用例、事务和权限，不承载可复用领域计算。
- `interfaces` 只做协议转换、验证和错误映射。
- `infrastructure` 实现Repository、Outbox、外部适配和技术细节。

## 3. 数据主权

- SaaS/组织/权限由平台域拥有。
- 商品、价格、订单、支付、库存、促销快照分别由对应领域拥有。
- 报表、搜索、Redis和连接器映射只保存派生或集成数据，不反向覆盖核心事实。
- 鲸熵汇与其他渠道是平级连接器，不能拥有核心订单和库存主权。
