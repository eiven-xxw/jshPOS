/// Gate 7B 班次非销售现金与钱箱请求的只追加本地事实。
abstract final class Gate7bCashOperationSchema {
  static const int version = 9;

  static const String v9 = r'''
CREATE TABLE local_shift_cash_movement (
  movement_id TEXT NOT NULL PRIMARY KEY CHECK(length(movement_id)=26),
  tenant_id TEXT NOT NULL,
  shift_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL,
  cashier_id TEXT NOT NULL,
  business_date TEXT NOT NULL CHECK(business_date GLOB '????-??-??'),
  movement_type TEXT NOT NULL CHECK(movement_type IN ('CASH_IN','CASH_OUT','SAFE_DROP')),
  signed_amount_minor INTEGER NOT NULL CHECK(
    (movement_type='CASH_IN' AND signed_amount_minor>0) OR
    (movement_type IN ('CASH_OUT','SAFE_DROP') AND signed_amount_minor<0)
  ),
  currency TEXT NOT NULL CHECK(currency='CNY'),
  reason_code TEXT NOT NULL CHECK(length(reason_code) BETWEEN 2 AND 32),
  reason_text TEXT NOT NULL CHECK(length(reason_text) BETWEEN 1 AND 256),
  authorization_ref TEXT NOT NULL CHECK(length(authorization_ref) BETWEEN 16 AND 128),
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  shift_version INTEGER NOT NULL CHECK(shift_version>1),
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,movement_id),
  UNIQUE(tenant_id,command_id),
  UNIQUE(tenant_id,shift_id,shift_version),
  FOREIGN KEY(tenant_id,shift_id) REFERENCES local_shift(tenant_id,shift_id)
) STRICT;
CREATE INDEX idx_local_shift_cash_movement_shift
  ON local_shift_cash_movement(tenant_id,shift_id,occurred_at);

CREATE TABLE local_drawer_event (
  drawer_event_id TEXT NOT NULL PRIMARY KEY CHECK(length(drawer_event_id)=26),
  tenant_id TEXT NOT NULL,
  shift_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL,
  cashier_id TEXT NOT NULL,
  business_date TEXT NOT NULL CHECK(business_date GLOB '????-??-??'),
  event_type TEXT NOT NULL CHECK(event_type='NO_SALE_OPEN_REQUESTED'),
  reason_code TEXT NOT NULL CHECK(length(reason_code) BETWEEN 2 AND 32),
  reason_text TEXT NOT NULL CHECK(length(reason_text) BETWEEN 1 AND 256),
  authorization_ref TEXT NOT NULL CHECK(length(authorization_ref) BETWEEN 16 AND 128),
  device_execution_status TEXT NOT NULL CHECK(device_execution_status='BLOCKED_EXTERNAL'),
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  shift_version INTEGER NOT NULL CHECK(shift_version>1),
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,drawer_event_id),
  UNIQUE(tenant_id,command_id),
  UNIQUE(tenant_id,shift_id,shift_version),
  FOREIGN KEY(tenant_id,shift_id) REFERENCES local_shift(tenant_id,shift_id)
) STRICT;
CREATE INDEX idx_local_drawer_event_shift
  ON local_drawer_event(tenant_id,shift_id,occurred_at);

CREATE TRIGGER local_shift_cash_movement_no_update BEFORE UPDATE ON local_shift_cash_movement
BEGIN SELECT RAISE(ABORT,'shift cash movement is append-only'); END;
CREATE TRIGGER local_shift_cash_movement_no_delete BEFORE DELETE ON local_shift_cash_movement
BEGIN SELECT RAISE(ABORT,'shift cash movement is append-only'); END;
CREATE TRIGGER local_drawer_event_no_update BEFORE UPDATE ON local_drawer_event
BEGIN SELECT RAISE(ABORT,'drawer event is append-only'); END;
CREATE TRIGGER local_drawer_event_no_delete BEFORE DELETE ON local_drawer_event
BEGIN SELECT RAISE(ABORT,'drawer event is append-only'); END;
''';
}
