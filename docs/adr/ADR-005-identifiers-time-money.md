# ADR-005：标识、时间与金额

- 状态：Accepted
- 日期：2026-08-15

RuoYi平台主键沿用BIGINT；业务租户号沿用VARCHAR(20)；离线创建实体使用ULID。时间在服务端存UTC `datetime(3)`、接口用ISO8601带时区；业务日期独立保存。结算金额用最小货币单位整数，单价/成本/税率使用DECIMAL/BigDecimal，数量默认DECIMAL(19,6)。
