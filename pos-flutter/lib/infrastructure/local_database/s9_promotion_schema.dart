abstract final class S9PromotionSchema {
  static const int version = 3;

  static const String v3 = r'''
DROP TRIGGER local_order_line_immutable_update;
DROP TRIGGER local_order_line_immutable_delete;
DROP TRIGGER local_order_immutable_update;
DROP TRIGGER local_order_no_delete;

CREATE TABLE local_order_v3 (
  order_id TEXT NOT NULL PRIMARY KEY CHECK(length(order_id)=26),
  tenant_id TEXT NOT NULL,
  local_order_no TEXT NOT NULL CHECK(length(local_order_no) BETWEEN 1 AND 40),
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL,
  shift_id TEXT NOT NULL,
  cashier_id TEXT NOT NULL,
  business_date TEXT NOT NULL CHECK(business_date GLOB '????-??-??'),
  store_timezone TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('DRAFT','PENDING_PAYMENT','CONFIRMED','COMPLETED','CANCELLED','CLOSED')),
  draft_disposition TEXT NOT NULL CHECK(draft_disposition IN ('ACTIVE','SUSPENDED')),
  payment_status TEXT NOT NULL CHECK(payment_status IN ('UNPAID','PAID')),
  currency TEXT NOT NULL CHECK(currency='CNY'),
  gross_amount_minor INTEGER NOT NULL CHECK(gross_amount_minor>=0),
  discount_amount_minor INTEGER NOT NULL CHECK(discount_amount_minor>=0),
  surcharge_amount_minor INTEGER NOT NULL CHECK(surcharge_amount_minor=0),
  receivable_amount_minor INTEGER NOT NULL CHECK(receivable_amount_minor>=0
    AND receivable_amount_minor=gross_amount_minor-discount_amount_minor+surcharge_amount_minor),
  received_amount_minor INTEGER NOT NULL CHECK(received_amount_minor>=0),
  catalog_version INTEGER NOT NULL CHECK(catalog_version>0),
  price_version INTEGER NOT NULL CHECK(price_version>0),
  industry_template_version TEXT NOT NULL,
  snapshot_schema_version INTEGER,
  snapshot_json TEXT,
  snapshot_sha256 TEXT CHECK(snapshot_sha256 IS NULL OR length(snapshot_sha256)=64),
  idempotency_key TEXT,
  request_sha256 TEXT CHECK(request_sha256 IS NULL OR length(request_sha256)=64),
  occurred_at TEXT NOT NULL,
  record_version INTEGER NOT NULL DEFAULT 1 CHECK(record_version>0),
  UNIQUE(tenant_id,order_id),
  UNIQUE(tenant_id,terminal_id,local_order_no),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY(tenant_id,shift_id) REFERENCES local_shift(tenant_id,shift_id)
) STRICT;
INSERT INTO local_order_v3 SELECT * FROM local_order;

CREATE TABLE local_order_line_v3 (
  line_id TEXT NOT NULL PRIMARY KEY CHECK(length(line_id)=26),
  tenant_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  line_no INTEGER NOT NULL CHECK(line_no BETWEEN 1 AND 500),
  sku_id TEXT NOT NULL CHECK(sku_id NOT GLOB '*[^0-9]*'
    AND substr(sku_id,1,1) BETWEEN '1' AND '9' AND length(sku_id) BETWEEN 1 AND 19),
  sku_code TEXT NOT NULL,
  barcode_value TEXT,
  product_name_snapshot TEXT NOT NULL,
  unit_id TEXT NOT NULL CHECK(unit_id NOT GLOB '*[^0-9]*'
    AND substr(unit_id,1,1) BETWEEN '1' AND '9' AND length(unit_id) BETWEEN 1 AND 19),
  unit_code TEXT NOT NULL,
  quantity_decimal TEXT NOT NULL,
  unit_price_minor INTEGER NOT NULL CHECK(unit_price_minor>=0),
  gross_amount_minor INTEGER NOT NULL CHECK(gross_amount_minor>=0),
  discount_amount_minor INTEGER NOT NULL CHECK(discount_amount_minor>=0),
  surcharge_amount_minor INTEGER NOT NULL CHECK(surcharge_amount_minor=0),
  payable_amount_minor INTEGER NOT NULL CHECK(payable_amount_minor>=0
    AND payable_amount_minor=gross_amount_minor-discount_amount_minor+surcharge_amount_minor),
  price_source TEXT NOT NULL CHECK(price_source IN ('TENANT_BASE','STORE_OVERRIDE')),
  UNIQUE(tenant_id,line_id),
  UNIQUE(tenant_id,order_id,line_no),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order_v3(tenant_id,order_id)
) STRICT;
INSERT INTO local_order_line_v3 SELECT * FROM local_order_line;
DROP TABLE local_order_line;
DROP TABLE local_order;
ALTER TABLE local_order_v3 RENAME TO local_order;
ALTER TABLE local_order_line_v3 RENAME TO local_order_line;

CREATE TRIGGER local_order_line_immutable_update
BEFORE UPDATE ON local_order_line
WHEN (SELECT status FROM local_order WHERE tenant_id=OLD.tenant_id AND order_id=OLD.order_id)<>'DRAFT'
BEGIN SELECT RAISE(ABORT,'submitted order lines are immutable'); END;
CREATE TRIGGER local_order_line_immutable_delete BEFORE DELETE ON local_order_line
BEGIN SELECT RAISE(ABORT,'order lines cannot be deleted'); END;
CREATE TRIGGER local_order_immutable_update BEFORE UPDATE ON local_order WHEN OLD.status<>'DRAFT'
BEGIN SELECT RAISE(ABORT,'submitted order snapshot is immutable'); END;
CREATE TRIGGER local_order_no_delete BEFORE DELETE ON local_order
BEGIN SELECT RAISE(ABORT,'orders cannot be deleted'); END;

CREATE TABLE local_promotion_package_slot (
  slot_code TEXT NOT NULL CHECK(slot_code IN ('A','B')),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL CHECK(package_version>0),
  previous_version INTEGER NOT NULL CHECK(previous_version>=0 AND previous_version<package_version),
  schema_version TEXT NOT NULL CHECK(schema_version='1.0'),
  engine_version TEXT NOT NULL CHECK(engine_version='promotion-engine-1.0.0'),
  payload_blob BLOB NOT NULL,
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64),
  signature_blob BLOB NOT NULL,
  signing_key_id TEXT NOT NULL,
  generated_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  installed_at TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('STAGED','ACTIVE','RETIRED')),
  PRIMARY KEY(tenant_id,store_id,slot_code),
  UNIQUE(tenant_id,store_id,package_version)
) STRICT;

CREATE TABLE local_promotion_package_binding (
  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id=1),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  active_slot TEXT NOT NULL CHECK(active_slot IN ('A','B')),
  active_package_version INTEGER NOT NULL CHECK(active_package_version>0),
  active_payload_sha256 TEXT NOT NULL CHECK(length(active_payload_sha256)=64),
  switched_at TEXT NOT NULL,
  record_version INTEGER NOT NULL DEFAULT 1 CHECK(record_version>0),
  UNIQUE(tenant_id,store_id)
) STRICT;

CREATE TABLE local_promotion_quote (
  quote_id TEXT NOT NULL PRIMARY KEY CHECK(length(quote_id)=26),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL,
  pricing_request_id TEXT NOT NULL CHECK(length(pricing_request_id)=26),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  result_sha256 TEXT NOT NULL CHECK(length(result_sha256)=64),
  engine_version TEXT NOT NULL,
  package_version INTEGER NOT NULL CHECK(package_version>0),
  business_time TEXT NOT NULL,
  gross_amount_minor INTEGER NOT NULL CHECK(gross_amount_minor>=0),
  discount_amount_minor INTEGER NOT NULL CHECK(discount_amount_minor>=0),
  payable_amount_minor INTEGER NOT NULL CHECK(payable_amount_minor>=0
    AND gross_amount_minor=discount_amount_minor+payable_amount_minor),
  status TEXT NOT NULL CHECK(status IN ('CALCULATED','FROZEN','EXPIRED')),
  created_at TEXT NOT NULL,
  UNIQUE(tenant_id,quote_id),
  UNIQUE(tenant_id,store_id,terminal_id,pricing_request_id)
) STRICT;

CREATE TABLE local_promotion_quote_line (
  quote_line_id TEXT NOT NULL PRIMARY KEY CHECK(length(quote_line_id)=26),
  tenant_id TEXT NOT NULL,
  quote_id TEXT NOT NULL,
  source_line_id TEXT NOT NULL CHECK(length(source_line_id)=26),
  line_no INTEGER NOT NULL CHECK(line_no BETWEEN 1 AND 500),
  sku_id TEXT NOT NULL,
  quantity_decimal TEXT NOT NULL,
  unit_price_minor INTEGER NOT NULL CHECK(unit_price_minor>=0),
  gross_amount_minor INTEGER NOT NULL CHECK(gross_amount_minor>=0),
  discount_amount_minor INTEGER NOT NULL CHECK(discount_amount_minor>=0),
  payable_amount_minor INTEGER NOT NULL CHECK(payable_amount_minor>=0
    AND gross_amount_minor=discount_amount_minor+payable_amount_minor),
  UNIQUE(tenant_id,quote_line_id),
  UNIQUE(tenant_id,quote_id,line_no),
  FOREIGN KEY(tenant_id,quote_id) REFERENCES local_promotion_quote(tenant_id,quote_id)
) STRICT;

CREATE TABLE local_promotion_adjustment (
  adjustment_id TEXT NOT NULL PRIMARY KEY CHECK(length(adjustment_id)=26),
  tenant_id TEXT NOT NULL,
  quote_id TEXT NOT NULL,
  source_line_id TEXT,
  source_type TEXT NOT NULL CHECK(source_type='RULE'),
  source_id TEXT NOT NULL,
  calculation_stage TEXT NOT NULL,
  amount_minor INTEGER NOT NULL CHECK(amount_minor>=0),
  explanation_code TEXT NOT NULL,
  applied_flag INTEGER NOT NULL CHECK(applied_flag IN (0,1)),
  ordinal_no INTEGER NOT NULL CHECK(ordinal_no>0),
  UNIQUE(tenant_id,adjustment_id),
  UNIQUE(tenant_id,quote_id,ordinal_no),
  FOREIGN KEY(tenant_id,quote_id) REFERENCES local_promotion_quote(tenant_id,quote_id)
) STRICT;

CREATE TRIGGER local_promotion_package_no_delete BEFORE DELETE ON local_promotion_package_slot
BEGIN SELECT RAISE(ABORT,'promotion packages cannot be deleted'); END;
CREATE TRIGGER local_promotion_quote_no_update BEFORE UPDATE ON local_promotion_quote
BEGIN SELECT RAISE(ABORT,'promotion quote is immutable'); END;
CREATE TRIGGER local_promotion_quote_no_delete BEFORE DELETE ON local_promotion_quote
BEGIN SELECT RAISE(ABORT,'promotion quote is immutable'); END;
''';
}
