/// T2-LOT-001 社区超市批次、效期包、FEFO 成交冻结与本地不可变流水。
abstract final class Gate7cLotExpirySchema {
  static const int version = 15;

  static const String v15 = r'''
CREATE TABLE local_lot_package_slot (
  package_id TEXT NOT NULL PRIMARY KEY CHECK(length(package_id)=64 AND package_id NOT GLOB '*[^0-9a-f]*'),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  warehouse_id TEXT NOT NULL CHECK(length(warehouse_id)=26),
  industry TEXT NOT NULL CHECK(industry='COMMUNITY_SUPERMARKET'),
  industry_template_version_id TEXT NOT NULL,
  industry_template_sha256 TEXT NOT NULL CHECK(length(industry_template_sha256)=64 AND industry_template_sha256 NOT GLOB '*[^0-9a-f]*'),
  business_zone_id TEXT NOT NULL CHECK(length(business_zone_id) BETWEEN 1 AND 64),
  business_day_start TEXT NOT NULL CHECK(length(business_day_start) BETWEEN 5 AND 8),
  package_version INTEGER NOT NULL CHECK(package_version>0),
  previous_version INTEGER NOT NULL CHECK(previous_version>=0 AND previous_version<package_version),
  schema_version TEXT NOT NULL CHECK(schema_version='1.0'),
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64 AND payload_sha256 NOT GLOB '*[^0-9a-f]*'),
  signing_key_id TEXT NOT NULL CHECK(length(signing_key_id) BETWEEN 1 AND 128),
  generated_at TEXT NOT NULL,
  installed_at TEXT NOT NULL,
  record_count INTEGER NOT NULL CHECK(record_count BETWEEN 1 AND 100000),
  state TEXT NOT NULL CHECK(state IN ('STAGED','ACTIVE','SUPERSEDED','REJECTED')),
  UNIQUE(tenant_id,store_id,package_version)
) STRICT;

CREATE TABLE local_lot_package_binding (
  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id=1),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  active_package_version INTEGER NOT NULL CHECK(active_package_version>0),
  active_payload_sha256 TEXT NOT NULL CHECK(length(active_payload_sha256)=64),
  activated_at TEXT NOT NULL,
  UNIQUE(tenant_id,store_id),
  FOREIGN KEY(tenant_id,store_id,active_package_version)
    REFERENCES local_lot_package_slot(tenant_id,store_id,package_version)
) STRICT;

CREATE TABLE local_lot_policy (
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL,
  policy_version_id TEXT NOT NULL CHECK(length(policy_version_id)=26),
  sku_id TEXT NOT NULL,
  enabled INTEGER NOT NULL CHECK(enabled IN (0,1)),
  expiry_basis TEXT NOT NULL CHECK(expiry_basis IN ('PRODUCTION_DATE','RECEIVED_DATE','EXPLICIT_EXPIRY_DATE')),
  shelf_life_days INTEGER,
  near_expiry_days INTEGER NOT NULL CHECK(near_expiry_days BETWEEN 0 AND 3650),
  effective_from TEXT NOT NULL,
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64 AND content_sha256 NOT GLOB '*[^0-9a-f]*'),
  PRIMARY KEY(tenant_id,store_id,package_version,sku_id),
  FOREIGN KEY(tenant_id,store_id,package_version)
    REFERENCES local_lot_package_slot(tenant_id,store_id,package_version),
  CHECK((expiry_basis='EXPLICIT_EXPIRY_DATE' AND shelf_life_days IS NULL)
     OR (expiry_basis<>'EXPLICIT_EXPIRY_DATE' AND shelf_life_days BETWEEN 1 AND 36500))
) STRICT;

CREATE TABLE local_lot_balance (
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL,
  lot_id TEXT NOT NULL CHECK(length(lot_id)=26),
  warehouse_id TEXT NOT NULL CHECK(length(warehouse_id)=26),
  sku_id TEXT NOT NULL,
  base_unit_id TEXT NOT NULL,
  supplier_lot_code TEXT,
  internal_lot_code TEXT NOT NULL,
  production_date TEXT,
  received_date TEXT NOT NULL,
  expiry_date TEXT NOT NULL,
  policy_version_id TEXT NOT NULL CHECK(length(policy_version_id)=26),
  near_expiry_days INTEGER NOT NULL CHECK(near_expiry_days BETWEEN 0 AND 3650),
  quantity_decimal TEXT NOT NULL CHECK(length(quantity_decimal) BETWEEN 1 AND 32),
  last_ledger_sequence INTEGER NOT NULL CHECK(last_ledger_sequence>=0),
  source_sha256 TEXT NOT NULL CHECK(length(source_sha256)=64 AND source_sha256 NOT GLOB '*[^0-9a-f]*'),
  record_version INTEGER NOT NULL DEFAULT 0 CHECK(record_version>=0),
  PRIMARY KEY(tenant_id,store_id,package_version,lot_id),
  FOREIGN KEY(tenant_id,store_id,package_version)
    REFERENCES local_lot_package_slot(tenant_id,store_id,package_version),
  FOREIGN KEY(tenant_id,store_id,package_version,sku_id)
    REFERENCES local_lot_policy(tenant_id,store_id,package_version,sku_id)
) STRICT;
CREATE INDEX idx_local_lot_fefo ON local_lot_balance(
  tenant_id,store_id,package_version,warehouse_id,sku_id,expiry_date,received_date,lot_id
);

CREATE TABLE local_order_lot_allocation (
  allocation_id TEXT NOT NULL PRIMARY KEY CHECK(length(allocation_id)=26),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL,
  order_id TEXT NOT NULL CHECK(length(order_id)=26),
  order_line_id TEXT NOT NULL CHECK(length(order_line_id)=26),
  lot_id TEXT NOT NULL CHECK(length(lot_id)=26),
  sku_id TEXT NOT NULL,
  base_unit_id TEXT NOT NULL,
  quantity_decimal TEXT NOT NULL,
  policy_version_id TEXT NOT NULL CHECK(length(policy_version_id)=26),
  expiry_date TEXT NOT NULL,
  business_date TEXT NOT NULL,
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64 AND content_sha256 NOT GLOB '*[^0-9a-f]*'),
  created_at TEXT NOT NULL,
  UNIQUE(tenant_id,order_id,order_line_id,lot_id),
  FOREIGN KEY(tenant_id,order_line_id)
    REFERENCES local_order_line(tenant_id,line_id),
  FOREIGN KEY(tenant_id,store_id,package_version,lot_id)
    REFERENCES local_lot_balance(tenant_id,store_id,package_version,lot_id)
) STRICT;

CREATE TABLE local_lot_ledger (
  ledger_id TEXT NOT NULL PRIMARY KEY CHECK(length(ledger_id)=26),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL,
  lot_id TEXT NOT NULL CHECK(length(lot_id)=26),
  ledger_sequence INTEGER NOT NULL CHECK(ledger_sequence>0),
  quantity_before TEXT NOT NULL,
  quantity_delta TEXT NOT NULL,
  quantity_after TEXT NOT NULL,
  movement_type TEXT NOT NULL CHECK(movement_type='SALE_OUT'),
  order_id TEXT NOT NULL CHECK(length(order_id)=26),
  order_line_id TEXT NOT NULL CHECK(length(order_line_id)=26),
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  business_date TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,store_id,package_version,lot_id,ledger_sequence),
  UNIQUE(tenant_id,command_id,order_line_id,lot_id),
  FOREIGN KEY(tenant_id,store_id,package_version,lot_id)
    REFERENCES local_lot_balance(tenant_id,store_id,package_version,lot_id)
) STRICT;

CREATE TABLE local_order_lot_snapshot (
  order_id TEXT NOT NULL PRIMARY KEY CHECK(length(order_id)=26),
  tenant_id TEXT NOT NULL,
  package_version INTEGER NOT NULL CHECK(package_version>0),
  payload_json TEXT NOT NULL CHECK(json_valid(payload_json)),
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64 AND payload_sha256 NOT GLOB '*[^0-9a-f]*'),
  created_at TEXT NOT NULL,
  UNIQUE(tenant_id,order_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id)
) STRICT;

CREATE TRIGGER local_lot_package_no_delete BEFORE DELETE ON local_lot_package_slot
BEGIN SELECT RAISE(ABORT,'lot packages cannot be deleted'); END;
CREATE TRIGGER local_lot_package_identity_no_update BEFORE UPDATE ON local_lot_package_slot
WHEN NEW.package_id IS NOT OLD.package_id
  OR NEW.tenant_id IS NOT OLD.tenant_id
  OR NEW.store_id IS NOT OLD.store_id
  OR NEW.warehouse_id IS NOT OLD.warehouse_id
  OR NEW.industry IS NOT OLD.industry
  OR NEW.industry_template_version_id IS NOT OLD.industry_template_version_id
  OR NEW.industry_template_sha256 IS NOT OLD.industry_template_sha256
  OR NEW.business_zone_id IS NOT OLD.business_zone_id
  OR NEW.business_day_start IS NOT OLD.business_day_start
  OR NEW.package_version IS NOT OLD.package_version
  OR NEW.previous_version IS NOT OLD.previous_version
  OR NEW.schema_version IS NOT OLD.schema_version
  OR NEW.payload_sha256 IS NOT OLD.payload_sha256
  OR NEW.signing_key_id IS NOT OLD.signing_key_id
  OR NEW.generated_at IS NOT OLD.generated_at
  OR NEW.installed_at IS NOT OLD.installed_at
  OR NEW.record_count IS NOT OLD.record_count
BEGIN SELECT RAISE(ABORT,'lot package identity is immutable'); END;
CREATE TRIGGER local_lot_package_state_transition BEFORE UPDATE OF state ON local_lot_package_slot
WHEN NOT ((OLD.state='STAGED' AND NEW.state='ACTIVE') OR (OLD.state='ACTIVE' AND NEW.state='SUPERSEDED'))
BEGIN SELECT RAISE(ABORT,'lot package state transition is invalid'); END;
CREATE TRIGGER local_lot_binding_no_delete BEFORE DELETE ON local_lot_package_binding
BEGIN SELECT RAISE(ABORT,'lot package binding cannot be deleted'); END;
CREATE TRIGGER local_lot_binding_transition BEFORE UPDATE ON local_lot_package_binding
WHEN NEW.singleton_id IS NOT OLD.singleton_id
  OR NEW.tenant_id IS NOT OLD.tenant_id
  OR NEW.store_id IS NOT OLD.store_id
  OR NEW.active_package_version<>OLD.active_package_version+1
BEGIN SELECT RAISE(ABORT,'lot package binding transition is invalid'); END;
CREATE TRIGGER local_lot_policy_no_update BEFORE UPDATE ON local_lot_policy
BEGIN SELECT RAISE(ABORT,'lot policy package facts are immutable'); END;
CREATE TRIGGER local_lot_policy_no_delete BEFORE DELETE ON local_lot_policy
BEGIN SELECT RAISE(ABORT,'lot policy package facts are immutable'); END;
CREATE TRIGGER local_lot_balance_identity_no_update BEFORE UPDATE ON local_lot_balance
WHEN NEW.tenant_id IS NOT OLD.tenant_id
  OR NEW.store_id IS NOT OLD.store_id
  OR NEW.package_version IS NOT OLD.package_version
  OR NEW.lot_id IS NOT OLD.lot_id
  OR NEW.warehouse_id IS NOT OLD.warehouse_id
  OR NEW.sku_id IS NOT OLD.sku_id
  OR NEW.base_unit_id IS NOT OLD.base_unit_id
  OR NEW.supplier_lot_code IS NOT OLD.supplier_lot_code
  OR NEW.internal_lot_code IS NOT OLD.internal_lot_code
  OR NEW.production_date IS NOT OLD.production_date
  OR NEW.received_date IS NOT OLD.received_date
  OR NEW.expiry_date IS NOT OLD.expiry_date
  OR NEW.policy_version_id IS NOT OLD.policy_version_id
  OR NEW.near_expiry_days IS NOT OLD.near_expiry_days
  OR NEW.source_sha256 IS NOT OLD.source_sha256
BEGIN SELECT RAISE(ABORT,'lot balance identity is immutable'); END;
CREATE TRIGGER local_lot_balance_no_delete BEFORE DELETE ON local_lot_balance
BEGIN SELECT RAISE(ABORT,'lot balance cannot be deleted'); END;
CREATE TRIGGER local_order_lot_allocation_no_update BEFORE UPDATE ON local_order_lot_allocation
BEGIN SELECT RAISE(ABORT,'order lot allocation is immutable'); END;
CREATE TRIGGER local_order_lot_allocation_no_delete BEFORE DELETE ON local_order_lot_allocation
BEGIN SELECT RAISE(ABORT,'order lot allocation is immutable'); END;
CREATE TRIGGER local_lot_ledger_no_update BEFORE UPDATE ON local_lot_ledger
BEGIN SELECT RAISE(ABORT,'local lot ledger is append-only'); END;
CREATE TRIGGER local_lot_ledger_no_delete BEFORE DELETE ON local_lot_ledger
BEGIN SELECT RAISE(ABORT,'local lot ledger is append-only'); END;
CREATE TRIGGER local_order_lot_snapshot_no_update BEFORE UPDATE ON local_order_lot_snapshot
BEGIN SELECT RAISE(ABORT,'order lot snapshot is immutable'); END;
CREATE TRIGGER local_order_lot_snapshot_no_delete BEFORE DELETE ON local_order_lot_snapshot
BEGIN SELECT RAISE(ABORT,'order lot snapshot is immutable'); END;
''';
}
