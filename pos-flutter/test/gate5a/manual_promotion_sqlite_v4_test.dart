import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const _binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: 'Synthetic Alice',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  test('V4 manual policy and events are tenant-bound and immutable', () {
    final local = PosLocalDatabase.inMemory(_binding);
    addTearDown(local.close);
    final db = local.database;
    db.execute(
      '''INSERT INTO local_promotion_package_slot(
        slot_code,tenant_id,store_id,package_version,previous_version,schema_version,
        engine_version,payload_blob,payload_sha256,signature_blob,signing_key_id,
        generated_at,expires_at,installed_at,state)
        VALUES('A','TENANT_A','1101',31,30,'1.0','promotion-engine-1.0.0',?, ?, ?,
        'SYNTHETIC_KEY','2026-08-17T00:00:00Z','2026-08-18T00:00:00Z',
        '2026-08-17T00:00:01Z','ACTIVE')''',
      [
        Uint8List.fromList([1]),
        _sha('a'),
        Uint8List.fromList([2]),
      ],
    );
    db.execute(
      '''INSERT INTO local_promotion_quote(
        quote_id,tenant_id,store_id,terminal_id,pricing_request_id,request_sha256,
        result_sha256,engine_version,package_version,business_time,gross_amount_minor,
        discount_amount_minor,payable_amount_minor,status,created_at)
        VALUES('01K5R000000000000000000001','TENANT_A','1101',
        '01K2A000000000000000000011','01K5R000000000000000000002',?, ?,
        'promotion-engine-1.0.0',31,'2026-08-17T01:00:00Z',1000,100,900,
        'FROZEN','2026-08-17T01:00:01Z')''',
      [_sha('b'), _sha('c')],
    );
    db.execute(
      '''INSERT INTO local_promotion_manual_policy(
        tenant_id,store_id,package_version,policy_version_id,policy_sha256,
        without_approval_minor,with_approval_minor,minimum_line_payable_minor,
        maximum_rounding_minor,rounding_multiples_json,installed_at)
        VALUES('TENANT_A','1101',31,31,?,100,1000,20,9,'[1,10]',
        '2026-08-17T01:00:02Z')''',
      [_sha('d')],
    );
    db.execute(
      '''INSERT INTO local_promotion_manual_event(
        manual_event_id,tenant_id,authorization_id,event_sequence,state,command_id,
        request_sha256,quote_id,store_id,terminal_id,action_type,source_line_id,
        amount_or_rate,payment_method,before_fingerprint,preview_fingerprint,
        incremental_discount_minor,package_version,policy_version_id,policy_sha256,operator_user_id,
        approver_user_id,business_date,reason_code,reason_text,correlation_id,
        result_json,result_sha256,occurred_at)
        VALUES('01K5R000000000000000000003','TENANT_A',
        '01K5R000000000000000000004',1,'PENDING_APPROVAL',
        '01K5R000000000000000000005',?,'01K5R000000000000000000001',
        '1101','01K2A000000000000000000011','ORDER_AMOUNT_OFF',NULL,'140',
        'NON_CASH',?,?,140,31,31,?,'101',NULL,'2026-08-17','CUSTOMER_CARE',
        'synthetic reason','01K5R000000000000000000006','{}',?,
        '2026-08-17T01:00:03Z')''',
      [_sha('e'), _sha('c'), _sha('f'), _sha('d'), _sha('9')],
    );

    expect(
      db
          .select('SELECT state FROM local_promotion_manual_event')
          .single['state'],
      'PENDING_APPROVAL',
    );
    expect(
      () =>
          db.execute("UPDATE local_promotion_manual_event SET state='APPLIED'"),
      throwsA(isA<Exception>()),
    );
    expect(
      () => db.execute(
        '''INSERT INTO local_promotion_manual_policy(
          tenant_id,store_id,package_version,policy_version_id,policy_sha256,
          without_approval_minor,with_approval_minor,minimum_line_payable_minor,
          maximum_rounding_minor,rounding_multiples_json,installed_at)
          VALUES('TENANT_B','1101',31,32,?,100,1000,20,9,'[1,10]',
          '2026-08-17T01:00:04Z')''',
        [_sha('8')],
      ),
      throwsA(isA<Exception>()),
    );
    expect(db.select('PRAGMA foreign_key_check'), isEmpty);
  });

  test('V4 rejects self-approval at the storage boundary', () {
    final local = PosLocalDatabase.inMemory(_binding);
    addTearDown(local.close);
    final sql =
        local.database
                .select(
                  "SELECT sql FROM sqlite_master WHERE name='local_promotion_manual_event'",
                )
                .single['sql']
            as String;

    expect(sql, contains("approver_user_id<>operator_user_id"));
    expect(sql, contains("state='PENDING_APPROVAL'"));
  });
}

String _sha(String character) => List.filled(64, character).join();
