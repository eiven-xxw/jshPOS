/// PRM-003 POS 端不可变成交优惠、逐行来源分摊与退款恢复流水。
abstract final class S9TransactionSchema {
  static const int version = 5;

  static const String v5 = r'''
CREATE TABLE local_promotion_transaction_snapshot (
  snapshot_id TEXT NOT NULL PRIMARY KEY CHECK(length(snapshot_id)=26),
  tenant_id TEXT NOT NULL,
  order_id TEXT NOT NULL CHECK(length(order_id)=26),
  quote_id TEXT NOT NULL CHECK(length(quote_id)=26),
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL,
  business_date TEXT NOT NULL CHECK(business_date GLOB '????-??-??'),
  currency TEXT NOT NULL CHECK(currency='CNY'),
  quote_fingerprint TEXT NOT NULL CHECK(length(quote_fingerprint)=64),
  snapshot_sha256 TEXT NOT NULL CHECK(length(snapshot_sha256)=64),
  gross_amount_minor INTEGER NOT NULL CHECK(gross_amount_minor>=0),
  discount_amount_minor INTEGER NOT NULL CHECK(discount_amount_minor>=0),
  payable_amount_minor INTEGER NOT NULL CHECK(payable_amount_minor>=0
    AND gross_amount_minor=discount_amount_minor+payable_amount_minor),
  actor_user_id TEXT NOT NULL,
  correlation_id TEXT NOT NULL CHECK(length(correlation_id)=26),
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,snapshot_id),
  UNIQUE(tenant_id,order_id),
  UNIQUE(tenant_id,quote_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id),
  FOREIGN KEY(tenant_id,quote_id) REFERENCES local_promotion_quote(tenant_id,quote_id)
) STRICT;

CREATE TABLE local_promotion_transaction_allocation (
  allocation_id TEXT NOT NULL PRIMARY KEY CHECK(length(allocation_id)=26),
  tenant_id TEXT NOT NULL,
  snapshot_id TEXT NOT NULL CHECK(length(snapshot_id)=26),
  line_id TEXT NOT NULL CHECK(length(line_id)=26),
  line_no INTEGER NOT NULL CHECK(line_no BETWEEN 1 AND 500),
  sku_id TEXT NOT NULL,
  quantity_decimal TEXT NOT NULL CHECK(length(quantity_decimal) BETWEEN 1 AND 20
    AND quantity_decimal NOT GLOB '*[^0-9.]*'
    AND quantity_decimal NOT LIKE '.%'
    AND quantity_decimal NOT LIKE '%.'
    AND length(quantity_decimal) - length(replace(quantity_decimal, '.', '')) <= 1
    AND trim(quantity_decimal, '0.') <> ''),
  gross_amount_minor INTEGER NOT NULL CHECK(gross_amount_minor>=0),
  discount_amount_minor INTEGER NOT NULL CHECK(discount_amount_minor>=0),
  payable_amount_minor INTEGER NOT NULL CHECK(payable_amount_minor>=0
    AND gross_amount_minor=discount_amount_minor+payable_amount_minor),
  source_allocations_json TEXT NOT NULL CHECK(json_valid(source_allocations_json)),
  source_allocations_sha256 TEXT NOT NULL CHECK(length(source_allocations_sha256)=64),
  UNIQUE(tenant_id,snapshot_id,line_id),
  UNIQUE(tenant_id,snapshot_id,line_no),
  FOREIGN KEY(tenant_id,snapshot_id)
    REFERENCES local_promotion_transaction_snapshot(tenant_id,snapshot_id)
) STRICT;

CREATE TABLE local_promotion_refund_allocation_ledger (
  refund_allocation_id TEXT NOT NULL PRIMARY KEY CHECK(length(refund_allocation_id)=26),
  tenant_id TEXT NOT NULL,
  snapshot_id TEXT NOT NULL CHECK(length(snapshot_id)=26),
  refund_id TEXT NOT NULL CHECK(length(refund_id)=26),
  line_id TEXT NOT NULL CHECK(length(line_id)=26),
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  quantity_decimal TEXT NOT NULL CHECK(length(quantity_decimal) BETWEEN 1 AND 20
    AND quantity_decimal NOT GLOB '*[^0-9.]*'
    AND quantity_decimal NOT LIKE '.%'
    AND quantity_decimal NOT LIKE '%.'
    AND length(quantity_decimal) - length(replace(quantity_decimal, '.', '')) <= 1
    AND trim(quantity_decimal, '0.') <> ''),
  gross_amount_minor INTEGER NOT NULL CHECK(gross_amount_minor>=0),
  discount_amount_minor INTEGER NOT NULL CHECK(discount_amount_minor>=0),
  payable_amount_minor INTEGER NOT NULL CHECK(payable_amount_minor>=0
    AND gross_amount_minor=discount_amount_minor+payable_amount_minor),
  cumulative_quantity_decimal TEXT NOT NULL CHECK(length(cumulative_quantity_decimal) BETWEEN 1 AND 20
    AND cumulative_quantity_decimal NOT GLOB '*[^0-9.]*'
    AND cumulative_quantity_decimal NOT LIKE '.%'
    AND cumulative_quantity_decimal NOT LIKE '%.'
    AND length(cumulative_quantity_decimal) - length(replace(cumulative_quantity_decimal, '.', '')) <= 1
    AND trim(cumulative_quantity_decimal, '0.') <> ''),
  cumulative_gross_amount_minor INTEGER NOT NULL CHECK(cumulative_gross_amount_minor>=gross_amount_minor),
  cumulative_discount_amount_minor INTEGER NOT NULL CHECK(cumulative_discount_amount_minor>=discount_amount_minor),
  cumulative_payable_amount_minor INTEGER NOT NULL CHECK(cumulative_payable_amount_minor>=payable_amount_minor
    AND cumulative_gross_amount_minor=cumulative_discount_amount_minor+cumulative_payable_amount_minor),
  actor_user_id TEXT NOT NULL,
  correlation_id TEXT NOT NULL CHECK(length(correlation_id)=26),
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,refund_id,line_id),
  FOREIGN KEY(tenant_id,snapshot_id,line_id)
    REFERENCES local_promotion_transaction_allocation(tenant_id,snapshot_id,line_id)
) STRICT;

CREATE TRIGGER local_promotion_transaction_snapshot_no_update
BEFORE UPDATE ON local_promotion_transaction_snapshot
BEGIN SELECT RAISE(ABORT,'promotion transaction snapshot is immutable'); END;
CREATE TRIGGER local_promotion_transaction_snapshot_no_delete
BEFORE DELETE ON local_promotion_transaction_snapshot
BEGIN SELECT RAISE(ABORT,'promotion transaction snapshot is immutable'); END;
CREATE TRIGGER local_promotion_transaction_snapshot_device_guard
BEFORE INSERT ON local_promotion_transaction_snapshot
WHEN NOT EXISTS(
  SELECT 1 FROM local_device_binding b WHERE b.singleton_id=1
    AND b.tenant_id=NEW.tenant_id AND b.store_id=NEW.store_id AND b.terminal_id=NEW.terminal_id
)
BEGIN SELECT RAISE(ABORT,'promotion transaction device binding mismatch'); END;
CREATE TRIGGER local_promotion_transaction_allocation_no_update
BEFORE UPDATE ON local_promotion_transaction_allocation
BEGIN SELECT RAISE(ABORT,'promotion transaction allocation is immutable'); END;
CREATE TRIGGER local_promotion_transaction_allocation_no_delete
BEFORE DELETE ON local_promotion_transaction_allocation
BEGIN SELECT RAISE(ABORT,'promotion transaction allocation is immutable'); END;
CREATE TRIGGER local_promotion_refund_allocation_no_update
BEFORE UPDATE ON local_promotion_refund_allocation_ledger
BEGIN SELECT RAISE(ABORT,'promotion refund allocation is immutable'); END;
CREATE TRIGGER local_promotion_refund_allocation_no_delete
BEFORE DELETE ON local_promotion_refund_allocation_ledger
BEGIN SELECT RAISE(ABORT,'promotion refund allocation is immutable'); END;
''';
}
