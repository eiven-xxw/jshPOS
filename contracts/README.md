# 标准契约目录

- `openapi/`：HTTP API，采用 OpenAPI 3.1。
- `events/`：内部领域/集成事件信封 JSON Schema。
- `connectors/`：连接器 Manifest 和标准数据契约。

T0 只建立版本、命名、关联标识和校验骨架，不定义交易业务 endpoint。业务契约必须先关联 RTM 需求和 ADR，再进入实现。
