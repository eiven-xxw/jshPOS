abstract final class Gate2Schema {
  static const int version = 1;

  static const String v1 = r'''
CREATE TABLE IF NOT EXISTS local_schema_history (
  version INTEGER NOT NULL PRIMARY KEY,
  description TEXT NOT NULL,
  checksum_sha256 TEXT NOT NULL CHECK(length(checksum_sha256)=64),
  installed_at TEXT NOT NULL
) STRICT;

CREATE TABLE local_device_binding (
  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id=1),
  tenant_id TEXT NOT NULL CHECK(length(tenant_id) BETWEEN 1 AND 20),
  store_id TEXT NOT NULL CHECK(store_id NOT GLOB '*[^0-9]*'
    AND substr(store_id,1,1) BETWEEN '1' AND '9' AND length(store_id) BETWEEN 1 AND 19),
  terminal_id TEXT NOT NULL CHECK(length(terminal_id)=26),
  cashier_id TEXT NOT NULL CHECK(length(cashier_id) BETWEEN 1 AND 64),
  cashier_name TEXT NOT NULL CHECK(length(cashier_name) BETWEEN 1 AND 64),
  store_timezone TEXT NOT NULL CHECK(length(store_timezone) BETWEEN 1 AND 64),
  next_device_sequence INTEGER NOT NULL DEFAULT 1 CHECK(next_device_sequence>0),
  UNIQUE(tenant_id,store_id,terminal_id)
) STRICT;

CREATE TABLE local_shift (
  shift_id TEXT NOT NULL PRIMARY KEY CHECK(length(shift_id)=26),
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL,
  cashier_id TEXT NOT NULL,
  cashier_name_snapshot TEXT NOT NULL,
  business_date TEXT NOT NULL CHECK(business_date GLOB '????-??-??'),
  store_timezone TEXT NOT NULL,
  config_version INTEGER NOT NULL CHECK(config_version>0),
  status TEXT NOT NULL CHECK(status IN ('OPEN','CLOSING','CLOSED')),
  currency TEXT NOT NULL CHECK(currency='CNY'),
  opening_cash_minor INTEGER NOT NULL CHECK(opening_cash_minor>=0),
  theoretical_cash_minor INTEGER NOT NULL,
  actual_cash_minor INTEGER,
  difference_minor INTEGER,
  approval_id TEXT,
  opened_at TEXT NOT NULL,
  closed_at TEXT,
  record_version INTEGER NOT NULL DEFAULT 1 CHECK(record_version>0),
  UNIQUE(tenant_id,shift_id),
  FOREIGN KEY(tenant_id,store_id,terminal_id)
    REFERENCES local_device_binding(tenant_id,store_id,terminal_id),
  CHECK((status<>'CLOSED' AND actual_cash_minor IS NULL AND difference_minor IS NULL AND closed_at IS NULL)
     OR (status='CLOSED' AND actual_cash_minor IS NOT NULL AND difference_minor IS NOT NULL AND closed_at IS NOT NULL))
) STRICT;
CREATE UNIQUE INDEX uk_local_shift_terminal_active
  ON local_shift(tenant_id,store_id,terminal_id) WHERE status IN ('OPEN','CLOSING');
CREATE UNIQUE INDEX uk_local_shift_cashier_active
  ON local_shift(tenant_id,store_id,cashier_id) WHERE status IN ('OPEN','CLOSING');

CREATE TABLE local_shift_approval (
  approval_id TEXT NOT NULL PRIMARY KEY CHECK(length(approval_id)=26),
  tenant_id TEXT NOT NULL,
  shift_id TEXT NOT NULL,
  approver_id TEXT NOT NULL,
  approver_name_snapshot TEXT NOT NULL,
  reason_code TEXT NOT NULL,
  reason_text TEXT NOT NULL CHECK(length(reason_text) BETWEEN 1 AND 256),
  theoretical_cash_minor INTEGER NOT NULL,
  actual_cash_minor INTEGER NOT NULL CHECK(actual_cash_minor>=0),
  difference_minor INTEGER NOT NULL,
  expected_shift_version INTEGER NOT NULL CHECK(expected_shift_version>0),
  auth_proof_ref TEXT NOT NULL CHECK(length(auth_proof_ref) BETWEEN 16 AND 128),
  authenticated_at TEXT NOT NULL,
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  status TEXT NOT NULL CHECK(status='APPROVED'),
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,approval_id),
  UNIQUE(tenant_id,shift_id,status),
  UNIQUE(tenant_id,command_id),
  FOREIGN KEY(tenant_id,shift_id) REFERENCES local_shift(tenant_id,shift_id)
) STRICT;

CREATE TABLE local_order (
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
  discount_amount_minor INTEGER NOT NULL CHECK(discount_amount_minor=0),
  surcharge_amount_minor INTEGER NOT NULL CHECK(surcharge_amount_minor=0),
  receivable_amount_minor INTEGER NOT NULL CHECK(receivable_amount_minor=gross_amount_minor),
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

CREATE TABLE local_order_line (
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
  discount_amount_minor INTEGER NOT NULL CHECK(discount_amount_minor=0),
  surcharge_amount_minor INTEGER NOT NULL CHECK(surcharge_amount_minor=0),
  payable_amount_minor INTEGER NOT NULL CHECK(payable_amount_minor=gross_amount_minor),
  price_source TEXT NOT NULL CHECK(price_source IN ('TENANT_BASE','STORE_OVERRIDE')),
  UNIQUE(tenant_id,line_id),
  UNIQUE(tenant_id,order_id,line_no),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id)
) STRICT;

CREATE TABLE local_order_state_history (
  history_id TEXT NOT NULL PRIMARY KEY CHECK(length(history_id)=26),
  tenant_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  from_status TEXT,
  to_status TEXT NOT NULL,
  aggregate_version INTEGER NOT NULL CHECK(aggregate_version>0),
  actor_id TEXT NOT NULL,
  reason_code TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,history_id),
  UNIQUE(tenant_id,order_id,command_id,to_status),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id)
) STRICT;

CREATE TABLE local_cash_payment (
  payment_id TEXT NOT NULL PRIMARY KEY CHECK(length(payment_id)=26),
  tenant_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  shift_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status='SUCCEEDED'),
  currency TEXT NOT NULL CHECK(currency='CNY'),
  receivable_amount_minor INTEGER NOT NULL CHECK(receivable_amount_minor>=0),
  tendered_amount_minor INTEGER NOT NULL,
  change_amount_minor INTEGER NOT NULL,
  net_amount_minor INTEGER NOT NULL,
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,payment_id),
  UNIQUE(tenant_id,order_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id),
  FOREIGN KEY(tenant_id,shift_id) REFERENCES local_shift(tenant_id,shift_id),
  CHECK(tendered_amount_minor>=receivable_amount_minor
    AND change_amount_minor=tendered_amount_minor-receivable_amount_minor
    AND net_amount_minor=receivable_amount_minor)
) STRICT;

CREATE TABLE local_cash_ledger (
  ledger_id TEXT NOT NULL PRIMARY KEY CHECK(length(ledger_id)=26),
  tenant_id TEXT NOT NULL,
  shift_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  payment_id TEXT NOT NULL,
  movement_type TEXT NOT NULL CHECK(movement_type='SALE_RECEIPT'),
  signed_amount_minor INTEGER NOT NULL CHECK(signed_amount_minor>=0),
  currency TEXT NOT NULL CHECK(currency='CNY'),
  business_date TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,ledger_id),
  UNIQUE(tenant_id,payment_id,movement_type),
  FOREIGN KEY(tenant_id,shift_id) REFERENCES local_shift(tenant_id,shift_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id),
  FOREIGN KEY(tenant_id,payment_id) REFERENCES local_cash_payment(tenant_id,payment_id)
) STRICT;

CREATE TABLE local_print_job (
  print_job_id TEXT NOT NULL PRIMARY KEY CHECK(length(print_job_id)=26),
  tenant_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('PENDING','PRINTED','FAILED')),
  template_version TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64),
  created_at TEXT NOT NULL,
  UNIQUE(tenant_id,print_job_id),
  UNIQUE(tenant_id,order_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id)
) STRICT;

CREATE TABLE local_outbox (
  event_id TEXT NOT NULL PRIMARY KEY CHECK(length(event_id)=26),
  tenant_id TEXT NOT NULL,
  device_sequence INTEGER NOT NULL CHECK(device_sequence>0),
  stream_code TEXT NOT NULL CHECK(stream_code IN ('order.command','shift.event')),
  event_type TEXT NOT NULL,
  aggregate_id TEXT NOT NULL CHECK(length(aggregate_id)=26),
  aggregate_version INTEGER NOT NULL CHECK(aggregate_version>0),
  correlation_id TEXT NOT NULL CHECK(length(correlation_id)=26),
  payload_json TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64),
  status TEXT NOT NULL CHECK(status IN ('PENDING','SENDING','RETRY','ACKED','FINAL_REJECTED')),
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
  created_at TEXT NOT NULL,
  UNIQUE(tenant_id,event_id),
  UNIQUE(tenant_id,device_sequence),
  UNIQUE(tenant_id,aggregate_id,aggregate_version,event_type)
) STRICT;

CREATE TABLE local_idempotency (
  idempotency_id TEXT NOT NULL PRIMARY KEY CHECK(length(idempotency_id)=26),
  tenant_id TEXT NOT NULL,
  command_type TEXT NOT NULL,
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  idempotency_key TEXT NOT NULL CHECK(length(idempotency_key) BETWEEN 16 AND 128),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  aggregate_id TEXT NOT NULL CHECK(length(aggregate_id)=26),
  result_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(tenant_id,idempotency_id),
  UNIQUE(tenant_id,command_type,idempotency_key),
  UNIQUE(tenant_id,command_id)
) STRICT;

CREATE TABLE local_audit_event (
  audit_id TEXT NOT NULL PRIMARY KEY CHECK(length(audit_id)=26),
  tenant_id TEXT NOT NULL,
  action_code TEXT NOT NULL,
  aggregate_type TEXT NOT NULL,
  aggregate_id TEXT NOT NULL CHECK(length(aggregate_id)=26),
  actor_id TEXT NOT NULL,
  approver_id TEXT,
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  trace_id TEXT NOT NULL,
  before_status TEXT,
  after_status TEXT NOT NULL,
  amount_minor INTEGER,
  currency TEXT,
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  reason_code TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,audit_id)
) STRICT;

CREATE TRIGGER local_order_line_immutable_update
BEFORE UPDATE ON local_order_line
WHEN (SELECT status FROM local_order WHERE tenant_id=OLD.tenant_id AND order_id=OLD.order_id)<>'DRAFT'
BEGIN SELECT RAISE(ABORT,'submitted order lines are immutable'); END;
CREATE TRIGGER local_order_line_immutable_delete
BEFORE DELETE ON local_order_line
BEGIN SELECT RAISE(ABORT,'order lines cannot be deleted'); END;
CREATE TRIGGER local_order_immutable_update
BEFORE UPDATE ON local_order WHEN OLD.status<>'DRAFT'
BEGIN SELECT RAISE(ABORT,'submitted order snapshot is immutable'); END;
CREATE TRIGGER local_order_no_delete BEFORE DELETE ON local_order
BEGIN SELECT RAISE(ABORT,'orders cannot be deleted'); END;
CREATE TRIGGER local_cash_payment_no_update BEFORE UPDATE ON local_cash_payment
BEGIN SELECT RAISE(ABORT,'cash payment is immutable'); END;
CREATE TRIGGER local_cash_payment_no_delete BEFORE DELETE ON local_cash_payment
BEGIN SELECT RAISE(ABORT,'cash payment cannot be deleted'); END;
CREATE TRIGGER local_cash_ledger_no_update BEFORE UPDATE ON local_cash_ledger
BEGIN SELECT RAISE(ABORT,'cash ledger is append-only'); END;
CREATE TRIGGER local_cash_ledger_no_delete BEFORE DELETE ON local_cash_ledger
BEGIN SELECT RAISE(ABORT,'cash ledger is append-only'); END;
CREATE TRIGGER local_order_history_no_update BEFORE UPDATE ON local_order_state_history
BEGIN SELECT RAISE(ABORT,'order history is append-only'); END;
CREATE TRIGGER local_order_history_no_delete BEFORE DELETE ON local_order_state_history
BEGIN SELECT RAISE(ABORT,'order history is append-only'); END;
CREATE TRIGGER local_audit_no_update BEFORE UPDATE ON local_audit_event
BEGIN SELECT RAISE(ABORT,'audit is append-only'); END;
CREATE TRIGGER local_audit_no_delete BEFORE DELETE ON local_audit_event
BEGIN SELECT RAISE(ABORT,'audit is append-only'); END;
CREATE TRIGGER local_shift_approval_no_update BEFORE UPDATE ON local_shift_approval
BEGIN SELECT RAISE(ABORT,'shift approval is immutable'); END;
CREATE TRIGGER local_shift_approval_no_delete BEFORE DELETE ON local_shift_approval
BEGIN SELECT RAISE(ABORT,'shift approval is immutable'); END;
''';
}
