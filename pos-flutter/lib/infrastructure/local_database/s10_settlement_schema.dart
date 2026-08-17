/// POS-006 促销成交绑定事实；只追加，不修改 Gate 2 / Gate 5A 已发布迁移。
abstract final class S10SettlementSchema {
  static const int version = 6;

  static const String v6 = r'''
CREATE TABLE local_checkout_settlement (
  settlement_id TEXT NOT NULL PRIMARY KEY CHECK(length(settlement_id)=26),
  tenant_id TEXT NOT NULL,
  order_id TEXT NOT NULL CHECK(length(order_id)=26),
  promotion_snapshot_id TEXT NOT NULL CHECK(length(promotion_snapshot_id)=26),
  quote_id TEXT NOT NULL CHECK(length(quote_id)=26),
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL,
  shift_id TEXT NOT NULL CHECK(length(shift_id)=26),
  business_date TEXT NOT NULL CHECK(business_date GLOB '????-??-??'),
  package_version INTEGER NOT NULL CHECK(package_version>0),
  quote_fingerprint TEXT NOT NULL CHECK(length(quote_fingerprint)=64),
  settlement_fingerprint TEXT NOT NULL CHECK(length(settlement_fingerprint)=64),
  manual_event_refs_json TEXT NOT NULL CHECK(json_valid(manual_event_refs_json)),
  basket_input_sha256 TEXT NOT NULL CHECK(length(basket_input_sha256)=64),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  order_snapshot_sha256 TEXT NOT NULL CHECK(length(order_snapshot_sha256)=64),
  promotion_snapshot_sha256 TEXT NOT NULL CHECK(length(promotion_snapshot_sha256)=64),
  gross_amount_minor INTEGER NOT NULL CHECK(gross_amount_minor>=0),
  discount_amount_minor INTEGER NOT NULL CHECK(discount_amount_minor>=0),
  surcharge_amount_minor INTEGER NOT NULL CHECK(surcharge_amount_minor>=0),
  receivable_amount_minor INTEGER NOT NULL CHECK(receivable_amount_minor>=0
    AND receivable_amount_minor=gross_amount_minor-discount_amount_minor+surcharge_amount_minor),
  status TEXT NOT NULL CHECK(status='COMMITTED'),
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,settlement_id),
  UNIQUE(tenant_id,order_id),
  UNIQUE(tenant_id,promotion_snapshot_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id),
  FOREIGN KEY(tenant_id,promotion_snapshot_id)
    REFERENCES local_promotion_transaction_snapshot(tenant_id,snapshot_id),
  FOREIGN KEY(tenant_id,quote_id) REFERENCES local_promotion_quote(tenant_id,quote_id),
  FOREIGN KEY(tenant_id,shift_id) REFERENCES local_shift(tenant_id,shift_id)
) STRICT;

CREATE TRIGGER local_checkout_settlement_guard
BEFORE INSERT ON local_checkout_settlement
WHEN NOT EXISTS(
  SELECT 1
  FROM local_order o
  JOIN local_promotion_transaction_snapshot s
    ON s.tenant_id=o.tenant_id AND s.order_id=o.order_id
  WHERE o.tenant_id=NEW.tenant_id AND o.order_id=NEW.order_id
    AND o.store_id=NEW.store_id AND o.terminal_id=NEW.terminal_id
    AND o.shift_id=NEW.shift_id AND o.business_date=NEW.business_date
    AND o.status='COMPLETED' AND o.payment_status='PAID'
    AND o.gross_amount_minor=NEW.gross_amount_minor
    AND o.discount_amount_minor=NEW.discount_amount_minor
    AND o.surcharge_amount_minor=NEW.surcharge_amount_minor
    AND o.receivable_amount_minor=NEW.receivable_amount_minor
    AND s.snapshot_id=NEW.promotion_snapshot_id AND s.quote_id=NEW.quote_id
    AND s.quote_fingerprint=NEW.settlement_fingerprint
    AND s.snapshot_sha256=NEW.promotion_snapshot_sha256
    AND s.gross_amount_minor=NEW.gross_amount_minor
    AND s.discount_amount_minor=NEW.discount_amount_minor
    AND s.payable_amount_minor=NEW.receivable_amount_minor
)
BEGIN SELECT RAISE(ABORT,'checkout settlement owner facts mismatch'); END;

CREATE TRIGGER local_checkout_settlement_no_update
BEFORE UPDATE ON local_checkout_settlement
BEGIN SELECT RAISE(ABORT,'checkout settlement is immutable'); END;
CREATE TRIGGER local_checkout_settlement_no_delete
BEFORE DELETE ON local_checkout_settlement
BEGIN SELECT RAISE(ABORT,'checkout settlement is immutable'); END;
''';
}
