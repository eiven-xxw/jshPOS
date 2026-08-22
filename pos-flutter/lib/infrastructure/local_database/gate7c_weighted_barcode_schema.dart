/// T2-PRD-005 已验签秤码模板投影；随商品包版本原子安装且只读不可变。
abstract final class Gate7cWeightedBarcodeSchema {
  static const int version = 14;

  static const String v14 = r'''
CREATE TABLE local_weighted_barcode_template (
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL CHECK(package_version>0),
  template_id TEXT NOT NULL,
  template_code TEXT NOT NULL,
  version_no INTEGER NOT NULL CHECK(version_no>0),
  scope_type TEXT NOT NULL CHECK(scope_type IN ('TENANT','STORE')),
  scope_store_id TEXT,
  barcode_kind TEXT NOT NULL CHECK(barcode_kind IN ('WEIGHT','AMOUNT')),
  symbology TEXT NOT NULL CHECK(symbology='EAN13'),
  prefix_value TEXT NOT NULL CHECK(length(prefix_value) BETWEEN 2 AND 5
    AND prefix_value NOT GLOB '*[^0-9]*'),
  total_length INTEGER NOT NULL CHECK(total_length=13),
  sku_start_pos INTEGER NOT NULL,
  sku_length INTEGER NOT NULL,
  value_start_pos INTEGER NOT NULL,
  value_length INTEGER NOT NULL,
  value_scale INTEGER NOT NULL CHECK(value_scale BETWEEN 0 AND 6
    AND (barcode_kind<>'AMOUNT' OR value_scale=2)),
  priority_no INTEGER NOT NULL CHECK(priority_no>=0),
  effective_from TEXT NOT NULL,
  effective_to TEXT,
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64
    AND content_sha256 NOT GLOB '*[^0-9a-f]*'),
  PRIMARY KEY(tenant_id,store_id,package_version,template_id),
  UNIQUE(tenant_id,store_id,package_version,template_code,version_no),
  FOREIGN KEY(tenant_id,store_id,package_version)
    REFERENCES local_catalog_package_slot(tenant_id,store_id,package_version),
  CHECK((scope_type='TENANT' AND scope_store_id IS NULL)
     OR (scope_type='STORE' AND scope_store_id=store_id)),
  CHECK(effective_to IS NULL OR effective_to>effective_from),
  CHECK(sku_start_pos>=1 AND sku_length BETWEEN 1 AND 8
    AND sku_start_pos>length(prefix_value) AND sku_start_pos+sku_length-1<=12),
  CHECK(value_start_pos>=1 AND value_length BETWEEN 1 AND 8
    AND value_start_pos>length(prefix_value) AND value_start_pos+value_length-1<=12),
  CHECK(sku_start_pos+sku_length<=value_start_pos
     OR value_start_pos+value_length<=sku_start_pos)
) STRICT;

ALTER TABLE local_order_line ADD COLUMN measurement_snapshot_json TEXT
  CHECK(measurement_snapshot_json IS NULL OR json_valid(measurement_snapshot_json));
ALTER TABLE local_order_line ADD COLUMN measurement_parse_sha256 TEXT
  CHECK((measurement_parse_sha256 IS NULL)=(measurement_snapshot_json IS NULL)
    AND (measurement_parse_sha256 IS NULL OR
      (length(measurement_parse_sha256)=64 AND measurement_parse_sha256 NOT GLOB '*[^0-9a-f]*')));
CREATE INDEX idx_local_weighted_barcode_resolve
  ON local_weighted_barcode_template(
    tenant_id,store_id,package_version,prefix_value,scope_type,priority_no,effective_from,effective_to
  );

CREATE TRIGGER local_weighted_barcode_no_update
BEFORE UPDATE ON local_weighted_barcode_template
BEGIN SELECT RAISE(ABORT,'weighted barcode package templates are immutable'); END;
CREATE TRIGGER local_weighted_barcode_no_delete
BEFORE DELETE ON local_weighted_barcode_template
BEGIN SELECT RAISE(ABORT,'weighted barcode package templates are immutable'); END;
''';
}
