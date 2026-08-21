/// Gate 6G 商品价格正式数据包投影；旧包保留用于安全回退，业务查询只读 ACTIVE 指针。
abstract final class Gate6gCatalogSchema {
  static const int version = 8;

  static const String v8 = r'''
CREATE TABLE local_catalog_package_slot (
  package_id TEXT NOT NULL PRIMARY KEY CHECK(length(package_id)=64),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL CHECK(package_version>0),
  previous_version INTEGER NOT NULL CHECK(previous_version>=0 AND previous_version<package_version),
  schema_version TEXT NOT NULL CHECK(schema_version IN ('1.0','0.9')),
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64),
  signing_key_id TEXT NOT NULL CHECK(length(signing_key_id) BETWEEN 1 AND 128),
  generated_at TEXT NOT NULL,
  record_count INTEGER NOT NULL CHECK(record_count>=0),
  installed_at TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('STAGED','ACTIVE','SUPERSEDED','REJECTED')),
  UNIQUE(tenant_id,store_id,package_version)
) STRICT;

CREATE TABLE local_catalog_package_binding (
  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id=1),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  active_package_version INTEGER NOT NULL CHECK(active_package_version>0),
  active_payload_sha256 TEXT NOT NULL CHECK(length(active_payload_sha256)=64),
  activated_at TEXT NOT NULL,
  UNIQUE(tenant_id,store_id),
  FOREIGN KEY(tenant_id,store_id,active_package_version)
    REFERENCES local_catalog_package_slot(tenant_id,store_id,package_version)
) STRICT;

CREATE TABLE local_catalog_product (
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL,
  sku_id TEXT NOT NULL,
  sku_code TEXT NOT NULL,
  product_name TEXT NOT NULL,
  product_type TEXT NOT NULL CHECK(product_type IN ('STANDARD','WEIGHT','COUNT')),
  category_id TEXT NOT NULL,
  brand_id TEXT,
  unit_id TEXT NOT NULL,
  unit_code TEXT NOT NULL,
  unit_name TEXT NOT NULL,
  decimal_scale INTEGER NOT NULL CHECK(decimal_scale BETWEEN 0 AND 6),
  ratio_numerator INTEGER NOT NULL CHECK(ratio_numerator>0),
  ratio_denominator INTEGER NOT NULL CHECK(ratio_denominator>0),
  barcode_value TEXT,
  PRIMARY KEY(tenant_id,store_id,package_version,sku_id,unit_id),
  FOREIGN KEY(tenant_id,store_id,package_version)
    REFERENCES local_catalog_package_slot(tenant_id,store_id,package_version)
) STRICT;
CREATE UNIQUE INDEX uk_local_catalog_barcode
  ON local_catalog_product(tenant_id,store_id,package_version,barcode_value)
  WHERE barcode_value IS NOT NULL;
CREATE INDEX idx_local_catalog_search
  ON local_catalog_product(tenant_id,store_id,package_version,sku_code,product_name);

CREATE TABLE local_catalog_price (
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL,
  price_book_id TEXT NOT NULL,
  book_code TEXT NOT NULL,
  version_no INTEGER NOT NULL CHECK(version_no>0),
  scope_type TEXT NOT NULL CHECK(scope_type IN ('TENANT_BASE','STORE')),
  scope_store_id TEXT,
  sku_id TEXT NOT NULL,
  unit_id TEXT NOT NULL,
  amount_minor INTEGER NOT NULL CHECK(amount_minor>=0),
  currency TEXT NOT NULL CHECK(currency='CNY'),
  effective_from TEXT NOT NULL,
  effective_to TEXT,
  PRIMARY KEY(tenant_id,store_id,package_version,price_book_id,sku_id,unit_id,effective_from),
  FOREIGN KEY(tenant_id,store_id,package_version)
    REFERENCES local_catalog_package_slot(tenant_id,store_id,package_version),
  CHECK((scope_type='TENANT_BASE' AND scope_store_id IS NULL)
     OR (scope_type='STORE' AND scope_store_id=store_id)),
  CHECK(effective_to IS NULL OR effective_to>effective_from)
) STRICT;
CREATE INDEX idx_local_catalog_price_resolve
  ON local_catalog_price(tenant_id,store_id,package_version,sku_id,unit_id,scope_type,effective_from,effective_to);

CREATE TRIGGER local_catalog_package_no_delete BEFORE DELETE ON local_catalog_package_slot
BEGIN SELECT RAISE(ABORT,'catalog packages cannot be deleted'); END;
CREATE TRIGGER local_catalog_product_no_update BEFORE UPDATE ON local_catalog_product
BEGIN SELECT RAISE(ABORT,'catalog package products are immutable'); END;
CREATE TRIGGER local_catalog_product_no_delete BEFORE DELETE ON local_catalog_product
BEGIN SELECT RAISE(ABORT,'catalog package products are immutable'); END;
CREATE TRIGGER local_catalog_price_no_update BEFORE UPDATE ON local_catalog_price
BEGIN SELECT RAISE(ABORT,'catalog package prices are immutable'); END;
CREATE TRIGGER local_catalog_price_no_delete BEFORE DELETE ON local_catalog_price
BEGIN SELECT RAISE(ABORT,'catalog package prices are immutable'); END;
''';
}
