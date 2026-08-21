/// EXG-001 换货命令日志前向迁移。
///
/// 本地只冻结已完成原退货与新销售的身份、金额和摘要；不创建独立资金、
/// 库存、促销或订单事实。UNKNOWN 只能复用原 exchange_id 查询。
abstract final class Gate7bExchangeSchema {
  static const int version = 12;

  static const String v12 = r'''
CREATE TABLE local_exchange_command (
  exchange_id TEXT NOT NULL CHECK(length(exchange_id)=26),
  tenant_id TEXT NOT NULL,
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  idempotency_key TEXT NOT NULL CHECK(length(idempotency_key) BETWEEN 16 AND 96),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  return_id TEXT NOT NULL CHECK(length(return_id)=26),
  original_order_id TEXT NOT NULL CHECK(length(original_order_id)=26),
  original_return_command_id TEXT NOT NULL CHECK(length(original_return_command_id)=26),
  new_order_id TEXT NOT NULL CHECK(length(new_order_id)=26),
  new_sale_command_id TEXT NOT NULL CHECK(length(new_sale_command_id)=26),
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL CHECK(length(terminal_id)=26),
  business_date TEXT NOT NULL CHECK(business_date GLOB '????-??-??'),
  expected_refund_amount_minor INTEGER NOT NULL CHECK(expected_refund_amount_minor>0),
  expected_sale_receivable_minor INTEGER NOT NULL CHECK(expected_sale_receivable_minor>0),
  quote_fingerprint TEXT NOT NULL CHECK(length(quote_fingerprint)=64),
  new_sale_plan_sha256 TEXT NOT NULL CHECK(length(new_sale_plan_sha256)=64),
  reason_code TEXT NOT NULL CHECK(length(reason_code) BETWEEN 2 AND 32),
  correlation_id TEXT NOT NULL CHECK(length(correlation_id)=26),
  server_status TEXT NOT NULL CHECK(server_status IN (
    'PREPARED','SUBMITTING','UNKNOWN','DRAFT','APPROVED','RETURN_PENDING','RETURN_UNKNOWN',
    'RETURN_COMPLETED','SALE_PENDING','SALE_UNKNOWN','COMPLETED','FAILED',
    'MANUAL_RECOVERY_REQUIRED','CLOSED')),
  server_record_version INTEGER,
  server_updated_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(tenant_id,exchange_id),
  UNIQUE(tenant_id,command_id),
  UNIQUE(tenant_id,idempotency_key),
  UNIQUE(tenant_id,return_id),
  UNIQUE(tenant_id,new_order_id),
  FOREIGN KEY(tenant_id,new_order_id) REFERENCES local_order(tenant_id,order_id),
  CHECK(original_order_id<>new_order_id)
) STRICT;

CREATE TABLE local_exchange_event (
  event_id TEXT NOT NULL PRIMARY KEY CHECK(length(event_id)=26),
  tenant_id TEXT NOT NULL,
  exchange_id TEXT NOT NULL CHECK(length(exchange_id)=26),
  event_type TEXT NOT NULL CHECK(event_type IN ('PREPARED','SUBMITTING','OBSERVED','UNKNOWN')),
  status TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64),
  occurred_at TEXT NOT NULL,
  FOREIGN KEY(tenant_id,exchange_id) REFERENCES local_exchange_command(tenant_id,exchange_id)
) STRICT;

CREATE INDEX idx_local_exchange_status
  ON local_exchange_command(tenant_id,server_status,updated_at,exchange_id);

CREATE TRIGGER local_exchange_identity_immutable BEFORE UPDATE ON local_exchange_command
WHEN OLD.exchange_id<>NEW.exchange_id OR OLD.tenant_id<>NEW.tenant_id
  OR OLD.command_id<>NEW.command_id OR OLD.idempotency_key<>NEW.idempotency_key
  OR OLD.request_sha256<>NEW.request_sha256 OR OLD.return_id<>NEW.return_id
  OR OLD.original_order_id<>NEW.original_order_id
  OR OLD.original_return_command_id<>NEW.original_return_command_id
  OR OLD.new_order_id<>NEW.new_order_id OR OLD.new_sale_command_id<>NEW.new_sale_command_id
  OR OLD.store_id<>NEW.store_id OR OLD.terminal_id<>NEW.terminal_id
  OR OLD.business_date<>NEW.business_date
  OR OLD.expected_refund_amount_minor<>NEW.expected_refund_amount_minor
  OR OLD.expected_sale_receivable_minor<>NEW.expected_sale_receivable_minor
  OR OLD.quote_fingerprint<>NEW.quote_fingerprint
  OR OLD.new_sale_plan_sha256<>NEW.new_sale_plan_sha256
  OR OLD.reason_code<>NEW.reason_code OR OLD.correlation_id<>NEW.correlation_id
  OR OLD.created_at<>NEW.created_at
BEGIN SELECT RAISE(ABORT,'exchange immutable identity changed'); END;

CREATE TRIGGER local_exchange_no_delete BEFORE DELETE ON local_exchange_command
BEGIN SELECT RAISE(ABORT,'exchange command cannot be deleted'); END;
CREATE TRIGGER local_exchange_event_no_update BEFORE UPDATE ON local_exchange_event
BEGIN SELECT RAISE(ABORT,'exchange event is append-only'); END;
CREATE TRIGGER local_exchange_event_no_delete BEFORE DELETE ON local_exchange_event
BEGIN SELECT RAISE(ABORT,'exchange event is append-only'); END;
''';
}
