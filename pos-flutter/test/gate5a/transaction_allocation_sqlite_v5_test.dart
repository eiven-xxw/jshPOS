import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: 'Synthetic Alice',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  test('SQLite V5 transaction and refund facts are bound and immutable', () {
    final db = PosLocalDatabase.inMemory(binding);
    addTearDown(db.close);
    _seedOwners(db);
    db.database.execute(
      'INSERT INTO local_promotion_transaction_snapshot(snapshot_id,tenant_id,order_id,quote_id,store_id,terminal_id,business_date,currency,quote_fingerprint,snapshot_sha256,gross_amount_minor,discount_amount_minor,payable_amount_minor,actor_user_id,correlation_id,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
      [
        '01K5S000000000000000000001',
        'TENANT_A',
        '01K5N000000000000000000001',
        '01K5Q000000000000000000001',
        '1101',
        binding.terminalId,
        '2026-08-17',
        'CNY',
        _hash('a'),
        _hash('b'),
        1500,
        101,
        1399,
        '101',
        '01K5S000000000000000000002',
        '2026-08-17T04:00:00Z',
      ],
    );
    db.database.execute(
      'INSERT INTO local_promotion_transaction_allocation(allocation_id,tenant_id,snapshot_id,line_id,line_no,sku_id,quantity_decimal,gross_amount_minor,discount_amount_minor,payable_amount_minor,source_allocations_json,source_allocations_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
      [
        '01K5A000000000000000000001',
        'TENANT_A',
        '01K5S000000000000000000001',
        '01K5R000000000000000000001',
        1,
        '101',
        '3',
        1000,
        101,
        899,
        '{}',
        _hash('c'),
      ],
    );
    db.database.execute(
      'INSERT INTO local_promotion_refund_allocation_ledger(refund_allocation_id,tenant_id,snapshot_id,refund_id,line_id,command_id,request_sha256,quantity_decimal,gross_amount_minor,discount_amount_minor,payable_amount_minor,cumulative_quantity_decimal,cumulative_gross_amount_minor,cumulative_discount_amount_minor,cumulative_payable_amount_minor,actor_user_id,correlation_id,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
      [
        '01K5F000000000000000000001',
        'TENANT_A',
        '01K5S000000000000000000001',
        '01K5F000000000000000000002',
        '01K5R000000000000000000001',
        '01K5F000000000000000000003',
        _hash('d'),
        '1',
        333,
        34,
        299,
        '1',
        333,
        34,
        299,
        '101',
        '01K5F000000000000000000004',
        '2026-08-17T04:01:00Z',
      ],
    );
    expect(
      () => db.database.execute(
        'UPDATE local_promotion_transaction_snapshot SET payable_amount_minor=1',
      ),
      throwsA(isA<Exception>()),
    );
    expect(
      () => db.database.execute(
        'DELETE FROM local_promotion_refund_allocation_ledger',
      ),
      throwsA(isA<Exception>()),
    );
    for (final invalidQuantity in ['0', '.1', '1.', '1..2']) {
      expect(
        () => db.database.execute(
          'INSERT INTO local_promotion_transaction_allocation(allocation_id,tenant_id,snapshot_id,line_id,line_no,sku_id,quantity_decimal,gross_amount_minor,discount_amount_minor,payable_amount_minor,source_allocations_json,source_allocations_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
          [
            '01K5A00000000000000000000${invalidQuantity.length}',
            'TENANT_A',
            '01K5S000000000000000000001',
            '01K5R00000000000000000000${invalidQuantity.length}',
            10 + invalidQuantity.length,
            '102',
            invalidQuantity,
            100,
            1,
            99,
            '{}',
            _hash('e'),
          ],
        ),
        throwsA(isA<Exception>()),
      );
    }
    expect(db.database.select('PRAGMA foreign_key_check'), isEmpty);
  });

  test('SQLite V5 rejects cross-device transaction snapshot', () {
    final db = PosLocalDatabase.inMemory(binding);
    addTearDown(db.close);
    _seedOwners(db);
    expect(
      () => db.database.execute(
        'INSERT INTO local_promotion_transaction_snapshot(snapshot_id,tenant_id,order_id,quote_id,store_id,terminal_id,business_date,currency,quote_fingerprint,snapshot_sha256,gross_amount_minor,discount_amount_minor,payable_amount_minor,actor_user_id,correlation_id,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
        [
          '01K5S000000000000000000001',
          'TENANT_A',
          '01K5N000000000000000000001',
          '01K5Q000000000000000000001',
          '1101',
          'OTHER-TERMINAL',
          '2026-08-17',
          'CNY',
          _hash('a'),
          _hash('b'),
          1500,
          101,
          1399,
          '101',
          '01K5S000000000000000000002',
          '2026-08-17T04:00:00Z',
        ],
      ),
      throwsA(isA<Exception>()),
    );
  });
}

void _seedOwners(PosLocalDatabase db) {
  db.database.execute(
    "INSERT INTO local_shift(shift_id,tenant_id,store_id,terminal_id,cashier_id,cashier_name_snapshot,business_date,store_timezone,config_version,status,currency,opening_cash_minor,theoretical_cash_minor,opened_at) VALUES('01K5H000000000000000000001','TENANT_A','1101','${binding.terminalId}','101','Synthetic Alice','2026-08-17','Asia/Shanghai',1,'OPEN','CNY',0,0,'2026-08-17T04:00:00Z')",
  );
  db.database.execute(
    "INSERT INTO local_order(order_id,tenant_id,local_order_no,store_id,terminal_id,shift_id,cashier_id,business_date,store_timezone,status,draft_disposition,payment_status,currency,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,received_amount_minor,catalog_version,price_version,industry_template_version,occurred_at) VALUES('01K5N000000000000000000001','TENANT_A','SYN-001','1101','${binding.terminalId}','01K5H000000000000000000001','101','2026-08-17','Asia/Shanghai','COMPLETED','ACTIVE','PAID','CNY',1500,101,0,1399,1399,1,1,'CONVENIENCE_V1','2026-08-17T04:00:00Z')",
  );
  db.database.execute(
    "INSERT INTO local_promotion_quote(quote_id,tenant_id,store_id,terminal_id,pricing_request_id,request_sha256,result_sha256,engine_version,package_version,business_time,gross_amount_minor,discount_amount_minor,payable_amount_minor,status,created_at) VALUES('01K5Q000000000000000000001','TENANT_A','1101','${binding.terminalId}','01K5Q000000000000000000002','${_hash('c')}','${_hash('d')}','promotion-engine-1.0.0',1,'2026-08-17T04:00:00Z',1500,101,1399,'FROZEN','2026-08-17T04:00:00Z')",
  );
}

String _hash(String character) => List.filled(64, character).join();
