-- T2-PRD-005：订单行冻结已验真的秤码/金额码快照；标准商品五列均为空。
ALTER TABLE ord_order_line
    ADD COLUMN measurement_template_id BIGINT NULL COMMENT '计量模板主键快照' AFTER price_source,
    ADD COLUMN measurement_template_version INT NULL COMMENT '计量模板版本快照' AFTER measurement_template_id,
    ADD COLUMN measurement_template_sha256 CHAR(64) NULL COMMENT '计量模板内容摘要' AFTER measurement_template_version,
    ADD COLUMN measurement_parse_sha256 CHAR(64) NULL COMMENT '计量解析摘要' AFTER measurement_template_sha256,
    ADD COLUMN measurement_snapshot_json JSON NULL COMMENT '不可变成交计量快照' AFTER measurement_parse_sha256,
    ADD KEY idx_ord_line_measurement_template (
        tenant_id, measurement_template_id, measurement_template_version
    ),
    ADD CONSTRAINT ck_ord_line_measurement_shape CHECK (
        (measurement_template_id IS NULL AND measurement_template_version IS NULL
          AND measurement_template_sha256 IS NULL AND measurement_parse_sha256 IS NULL
          AND measurement_snapshot_json IS NULL)
        OR
        (measurement_template_id IS NOT NULL AND measurement_template_id > 0
          AND measurement_template_version IS NOT NULL AND measurement_template_version > 0
          AND measurement_template_sha256 REGEXP '^[a-f0-9]{64}$'
          AND measurement_parse_sha256 REGEXP '^[a-f0-9]{64}$'
          AND measurement_snapshot_json IS NOT NULL)
    );
