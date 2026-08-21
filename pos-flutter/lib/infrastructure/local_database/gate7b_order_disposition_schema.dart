/// Gate 7B ORD-004 交易取消与成交后处置路由前向迁移。
///
/// 处置事实只追加；取消只改变未完成订单状态，成交后的路由不得改写订单、
/// 支付、退款、库存、成本或促销历史。
abstract final class Gate7bOrderDispositionSchema {
  static const int version = 11;

  static const String v11 = r'''
CREATE TABLE local_order_disposition (
  disposition_id TEXT NOT NULL PRIMARY KEY CHECK(length(disposition_id)=26),
  tenant_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL,
  shift_id TEXT NOT NULL,
  cashier_id TEXT NOT NULL,
  business_date TEXT NOT NULL CHECK(business_date GLOB '????-??-??'),
  disposition_type TEXT NOT NULL CHECK(disposition_type IN (
    'CANCEL_BEFORE_COMPLETION','RETURN_REFUND_REQUIRED',
    'PAYMENT_REVERSAL_OBSERVATION_REQUIRED','EXPLICIT_COMPENSATION_REQUIRED')),
  from_status TEXT NOT NULL CHECK(from_status IN ('DRAFT','PENDING_PAYMENT','CONFIRMED','COMPLETED')),
  effective_status TEXT NOT NULL CHECK(effective_status IN ('CANCELLED','CONFIRMED','COMPLETED')),
  reason_code TEXT NOT NULL CHECK(length(reason_code) BETWEEN 2 AND 32),
  reason_text TEXT NOT NULL CHECK(length(reason_text) BETWEEN 1 AND 256),
  authorization_ref TEXT CHECK(authorization_ref IS NULL OR length(authorization_ref) BETWEEN 16 AND 128),
  order_snapshot_sha256 TEXT NOT NULL CHECK(length(order_snapshot_sha256)=64),
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  idempotency_key TEXT NOT NULL CHECK(length(idempotency_key) BETWEEN 16 AND 128),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  aggregate_version INTEGER NOT NULL CHECK(aggregate_version>0),
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,disposition_id),
  UNIQUE(tenant_id,command_id),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id),
  CHECK((disposition_type='CANCEL_BEFORE_COMPLETION'
         AND from_status IN ('DRAFT','PENDING_PAYMENT') AND effective_status='CANCELLED')
     OR (disposition_type<>'CANCEL_BEFORE_COMPLETION'
         AND from_status IN ('CONFIRMED','COMPLETED') AND effective_status=from_status))
) STRICT;

CREATE UNIQUE INDEX uk_local_order_single_cancel
  ON local_order_disposition(tenant_id,order_id)
  WHERE disposition_type='CANCEL_BEFORE_COMPLETION';

CREATE TRIGGER local_order_disposition_no_update BEFORE UPDATE ON local_order_disposition
BEGIN SELECT RAISE(ABORT,'order disposition is append-only'); END;
CREATE TRIGGER local_order_disposition_no_delete BEFORE DELETE ON local_order_disposition
BEGIN SELECT RAISE(ABORT,'order disposition is append-only'); END;
''';
}
