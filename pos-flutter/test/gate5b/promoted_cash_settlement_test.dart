import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/application/checkout_local_service.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
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
  group('T2-POS-006 promoted local atomic settlement', () {
    test('freezes quote, snapshot, order, cash and outbox atomically', () {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      final command = fixture.command();

      final result = fixture.service.completePromotedCashSale(command);

      expect(result.receivableAmountMinor, 900);
      expect(result.changeAmountMinor, 1100);
      expect(fixture.count('local_checkout_settlement'), 1);
      expect(fixture.count('local_promotion_transaction_snapshot'), 1);
      expect(fixture.count('local_promotion_transaction_allocation'), 1);
      expect(fixture.count('local_order'), 1);
      expect(
        fixture.scalar('SELECT discount_amount_minor FROM local_order'),
        100,
      );
      expect(
        fixture.scalar('SELECT discount_amount_minor FROM local_order_line'),
        100,
      );
      expect(fixture.count('local_cash_payment'), 1);
      expect(fixture.count('local_cash_ledger'), 1);
      expect(
        fixture.scalar(
          "SELECT status FROM local_outbox WHERE event_type='order.completed.v2'",
        ),
        'PENDING',
      );
      expect(
        fixture.scalar(
          'SELECT theoretical_cash_minor FROM local_shift WHERE shift_id=?',
          [fixture.shiftId],
        ),
        900,
      );
      expect(fixture.db.database.select('PRAGMA foreign_key_check'), isEmpty);
    });

    test(
      'same key resumes original result and changed content is rejected',
      () {
        final fixture = _Fixture();
        addTearDown(fixture.close);
        final command = fixture.command();
        final first = fixture.service.completePromotedCashSale(command);

        final duplicate = fixture.service.completePromotedCashSale(command);
        expect(duplicate.duplicate, isTrue);
        expect(duplicate.paymentId, first.paymentId);
        expect(fixture.count('local_checkout_settlement'), 1);

        expect(
          () => fixture.service.completePromotedCashSale(
            fixture.command(tenderedAmountMinor: 2500),
          ),
          throwsA(
            isA<PosDomainException>().having(
              (error) => error.code,
              'code',
              'IDEMPOTENCY_KEY_REUSED',
            ),
          ),
        );
      },
    );

    for (final checkpoint in [
      'promotion.inputs.verified',
      'promoted.order.snapshot',
      'promotion.snapshot',
      'checkout.settlement',
      'promoted.cash.payment',
      'promoted.cash.ledger',
      'promoted.outbox.appended',
      'promoted.audit.appended',
      'promoted.idempotency.saved',
    ]) {
      test('failure at $checkpoint rolls every settlement effect back', () {
        var armed = '';
        final fixture = _Fixture(
          failureInjector: (point) {
            if (point == armed) throw StateError('fixed failure $point');
          },
        );
        addTearDown(fixture.close);
        final beforeOutbox = fixture.count('local_outbox');
        final beforeAudit = fixture.count('local_audit_event');
        final beforeIdempotency = fixture.count('local_idempotency');
        armed = checkpoint;

        expect(
          () => fixture.service.completePromotedCashSale(fixture.command()),
          throwsStateError,
        );
        expect(fixture.count('local_checkout_settlement'), 0);
        expect(fixture.count('local_promotion_transaction_snapshot'), 0);
        expect(fixture.count('local_order'), 0);
        expect(fixture.count('local_cash_payment'), 0);
        expect(fixture.count('local_cash_ledger'), 0);
        expect(fixture.count('local_outbox'), beforeOutbox);
        expect(fixture.count('local_audit_event'), beforeAudit);
        expect(fixture.count('local_idempotency'), beforeIdempotency);
      });
    }

    test('retained retired package settles after active package changes', () {
      final fixture = _Fixture(activeNewerPackage: true);
      addTearDown(fixture.close);

      final result = fixture.service.completePromotedCashSale(
        fixture.command(),
      );

      expect(result.receivableAmountMinor, 900);
      expect(
        fixture.scalar('SELECT package_version FROM local_checkout_settlement'),
        1,
      );
    });

    test('authorized manual chain is frozen without recalculation', () {
      final fixture = _Fixture(withManualDiscount: true);
      addTearDown(fixture.close);

      final result = fixture.service.completePromotedCashSale(
        fixture.command(withManualDiscount: true),
      );

      expect(result.receivableAmountMinor, 850);
      expect(
        fixture.scalar('SELECT discount_amount_minor FROM local_order'),
        150,
      );
      expect(
        fixture.scalar(
          'SELECT settlement_fingerprint FROM local_checkout_settlement',
        ),
        _hash('b'),
      );
    });

    test('tampered fingerprint and cross-tenant settlement fail closed', () {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      expect(
        () => fixture.service.completePromotedCashSale(
          fixture.command(quoteFingerprint: _hash('f')),
        ),
        throwsA(
          isA<PosDomainException>().having(
            (error) => error.code,
            'code',
            'PROMOTION_QUOTE_MISMATCH',
          ),
        ),
      );

      fixture.service.completePromotedCashSale(fixture.command());
      expect(
        () => fixture.db.database.execute(
          "INSERT INTO local_checkout_settlement SELECT '01K5X000000000000000000001','TENANT_B',order_id,promotion_snapshot_id,quote_id,store_id,terminal_id,shift_id,business_date,package_version,quote_fingerprint,settlement_fingerprint,manual_event_refs_json,basket_input_sha256,request_sha256,order_snapshot_sha256,promotion_snapshot_sha256,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,status,occurred_at FROM local_checkout_settlement WHERE tenant_id='TENANT_A'",
        ),
        throwsA(isA<Exception>()),
      );
      expect(fixture.count('local_checkout_settlement'), 1);
    });

    test('V6 settlement binding is immutable', () {
      final fixture = _Fixture();
      addTearDown(fixture.close);
      fixture.service.completePromotedCashSale(fixture.command());

      expect(
        () => fixture.db.database.execute(
          'UPDATE local_checkout_settlement SET receivable_amount_minor=1',
        ),
        throwsA(isA<Exception>()),
      );
      expect(
        () => fixture.db.database.execute(
          'DELETE FROM local_checkout_settlement',
        ),
        throwsA(isA<Exception>()),
      );
    });

    test('member benefit snapshot is frozen in the same local transaction', () {
      final fixture = _Fixture(withMemberBenefit: true);
      addTearDown(fixture.close);
      final result = fixture.service.completePromotedCashSale(
        fixture.command(withMemberBenefit: true),
      );
      expect(result.receivableAmountMinor, 900);
      expect(fixture.count('local_order_member_benefit_snapshot'), 1);
      expect(
        fixture.scalar(
          'SELECT selected_path FROM local_order_member_benefit_snapshot',
        ),
        'MEMBER_PATH',
      );
      expect(
        fixture.scalar(
          "SELECT payload_json LIKE '%memberBenefitSnapshot%' FROM local_outbox WHERE event_type='order.completed.v2'",
        ),
        1,
      );
      expect(
        () => fixture.db.database.execute(
          'UPDATE local_order_member_benefit_snapshot SET selected_path=\'NORMAL_PATH\'',
        ),
        throwsA(isA<Exception>()),
      );
    });
  });
}

final class _Fixture {
  _Fixture({
    FailureInjector? failureInjector,
    bool activeNewerPackage = false,
    bool withManualDiscount = false,
    bool withMemberBenefit = false,
  }) : db = PosLocalDatabase.inMemory(
         _binding,
         failureInjector: failureInjector,
       ),
       ulids = UlidGenerator(
         random: Random(510),
         now: () => DateTime.utc(2026, 8, 17, 6),
       ) {
    service = CheckoutLocalService(
      localDatabase: db,
      ulids: ulids,
      shiftPolicy: const ShiftPolicy(cashDifferenceApprovalMinor: 100),
    );
    shiftId = service
        .openShift(
          commandId: '01K5H000000000000000000001',
          idempotencyKey: 'gate5b-open-shift-0001',
          businessDate: '2026-08-17',
          openingCashMinor: 0,
          configVersion: 1,
          occurredAt: DateTime.utc(2026, 8, 17, 5, 55),
        )
        .shiftId;
    _seedPromotion(
      activeNewerPackage: activeNewerPackage,
      withManualDiscount: withManualDiscount,
    );
    if (withMemberBenefit) _seedMemberBenefit();
  }

  final PosLocalDatabase db;
  final UlidGenerator ulids;
  late final CheckoutLocalService service;
  late final String shiftId;

  PromotedCashSaleCommand command({
    int tenderedAmountMinor = 2000,
    String? quoteFingerprint,
    bool withManualDiscount = false,
    bool withMemberBenefit = false,
  }) {
    final basketLine = BasketLine(
      lineId: '01K5R000000000000000000001',
      lineNo: 1,
      quote: PriceQuote.fromVerifiedPackage(
        skuId: '101',
        skuCode: 'SYN-SKU-101',
        productName: 'Synthetic Milk',
        unitId: '201',
        unitCode: 'PCS',
        unitPriceMinor: 500,
        priceSource: 'TENANT_BASE',
      ),
      quantity: '2',
    );
    final manualEventId = '01K5M000000000000000000001';
    final discount = withManualDiscount ? 150 : 100;
    return PromotedCashSaleCommand(
      commandId: '01K5C000000000000000000001',
      idempotencyKey: 'gate5b-promoted-sale-0001',
      basket: Basket(
        orderId: '01K5N000000000000000000001',
        localOrderNo: 'SYN-G5B-0001',
        lines: [basketLine],
      ),
      shiftId: shiftId,
      businessDate: '2026-08-17',
      catalogVersion: 10,
      priceVersion: 20,
      industryTemplateVersion: 'CONVENIENCE_V1',
      quoteId: '01K5Q000000000000000000001',
      quoteFingerprint: quoteFingerprint ?? _hash('a'),
      settlementFingerprint: withManualDiscount ? _hash('b') : _hash('a'),
      packageVersion: 1,
      promotionSnapshotId: '01K5S000000000000000000001',
      lines: [
        PromotedSettlementLine(
          basketLine: basketLine,
          discountAmountMinor: discount,
          sourceAllocations: {
            'RULE:RULE-001': 100,
            if (withManualDiscount) 'MANUAL:$manualEventId': 50,
          },
        ),
      ],
      manualEventRefs: withManualDiscount ? [manualEventId] : const [],
      tenderedAmountMinor: tenderedAmountMinor,
      occurredAt: DateTime.utc(2026, 8, 17, 6),
      memberBenefitSnapshot: withMemberBenefit
          ? MemberBenefitSettlementSnapshot(
              entitlementSnapshotId: '01K5E000000000000000000001',
              benefitVersionId: '01K5B000000000000000000001',
              selectedPath: 'MEMBER_PATH',
              memberPriceVersions: const ['01K5P000000000000000000001'],
              capabilityConfigVersion: 31,
              capabilitySha256: _hash('c'),
              rightsDigest: _hash('d'),
              explanationSha256: _hash('e'),
              packageVersion: 1,
              packageSha256: _hash('f'),
              contentSha256: _hash('9'),
            )
          : null,
    );
  }

  void _seedMemberBenefit() {
    db.database.execute(
      '''INSERT INTO local_member_benefit_package_slot(slot_code,tenant_id,store_id,package_version,
        previous_version,schema_version,engine_version,payload_blob,payload_sha256,signature_blob,
        signing_key_id,generated_at,expires_at,installed_at,state)
        VALUES('A',?,?,1,0,'1.0','member-benefit-engine-1.0.0',?,?,?,?,?,?,?,'ACTIVE')''',
      [
        _binding.tenantId,
        _binding.storeId,
        [1],
        _hash('f'),
        [2],
        'synthetic-member-key',
        '2026-08-17T05:00:00Z',
        '2026-09-17T05:00:00Z',
        '2026-08-17T05:00:00Z',
      ],
    );
    db.database.execute(
      '''INSERT INTO local_promotion_quote_member_benefit(tenant_id,quote_id,entitlement_snapshot_id,
        benefit_version_id,selected_path,member_price_versions_json,capability_config_version,
        capability_sha256,rights_digest,explanation_sha256,package_version,package_sha256,
        content_sha256,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
      [
        _binding.tenantId,
        '01K5Q000000000000000000001',
        '01K5E000000000000000000001',
        '01K5B000000000000000000001',
        'MEMBER_PATH',
        '["01K5P000000000000000000001"]',
        31,
        _hash('c'),
        _hash('d'),
        _hash('e'),
        1,
        _hash('f'),
        _hash('9'),
        '2026-08-17T05:59:00Z',
      ],
    );
  }

  void _seedPromotion({
    required bool activeNewerPackage,
    required bool withManualDiscount,
  }) {
    db.database.execute(
      'INSERT INTO local_promotion_package_slot(slot_code,tenant_id,store_id,package_version,previous_version,schema_version,engine_version,payload_blob,payload_sha256,signature_blob,signing_key_id,generated_at,expires_at,installed_at,state) VALUES(?,?,?,?,?,\'1.0\',\'promotion-engine-1.0.0\',?,?,?,?,?,?,?,?)',
      [
        'A',
        _binding.tenantId,
        _binding.storeId,
        1,
        0,
        [1, 2, 3],
        _hash('1'),
        [4, 5, 6],
        'synthetic-key-1',
        '2026-08-17T05:00:00Z',
        '2026-09-17T05:00:00Z',
        '2026-08-17T05:00:00Z',
        activeNewerPackage ? 'RETIRED' : 'ACTIVE',
      ],
    );
    if (activeNewerPackage) {
      db.database.execute(
        'INSERT INTO local_promotion_package_slot(slot_code,tenant_id,store_id,package_version,previous_version,schema_version,engine_version,payload_blob,payload_sha256,signature_blob,signing_key_id,generated_at,expires_at,installed_at,state) VALUES(\'B\',?,?,2,1,\'1.0\',\'promotion-engine-1.0.0\',?,?,?,?,?,?,?,\'ACTIVE\')',
        [
          _binding.tenantId,
          _binding.storeId,
          [7],
          _hash('2'),
          [8],
          'synthetic-key-2',
          '2026-08-17T05:30:00Z',
          '2026-09-17T05:30:00Z',
          '2026-08-17T05:30:00Z',
        ],
      );
    }
    db.database.execute(
      'INSERT INTO local_promotion_quote(quote_id,tenant_id,store_id,terminal_id,pricing_request_id,request_sha256,result_sha256,engine_version,package_version,business_time,gross_amount_minor,discount_amount_minor,payable_amount_minor,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,\'CALCULATED\',?)',
      [
        '01K5Q000000000000000000001',
        _binding.tenantId,
        _binding.storeId,
        _binding.terminalId,
        '01K5Q000000000000000000002',
        _hash('0'),
        _hash('a'),
        'promotion-engine-1.0.0',
        1,
        '2026-08-17T05:59:00Z',
        1000,
        100,
        900,
        '2026-08-17T05:59:00Z',
      ],
    );
    db.database.execute(
      'INSERT INTO local_promotion_quote_line(quote_line_id,tenant_id,quote_id,source_line_id,line_no,sku_id,quantity_decimal,unit_price_minor,gross_amount_minor,discount_amount_minor,payable_amount_minor) VALUES(?,?,?,?,?,?,?,?,?,?,?)',
      [
        '01K5L000000000000000000001',
        _binding.tenantId,
        '01K5Q000000000000000000001',
        '01K5R000000000000000000001',
        1,
        '101',
        '2',
        500,
        1000,
        100,
        900,
      ],
    );
    if (withManualDiscount) {
      db.database.execute(
        'INSERT INTO local_promotion_manual_policy(tenant_id,store_id,package_version,policy_version_id,policy_sha256,without_approval_minor,with_approval_minor,minimum_line_payable_minor,maximum_rounding_minor,rounding_multiples_json,installed_at) VALUES(?,?,1,1,?,10,100,1,5,\'[1,5]\',?)',
        [
          _binding.tenantId,
          _binding.storeId,
          _hash('p'),
          '2026-08-17T05:00:00Z',
        ],
      );
      db.database.execute(
        'INSERT INTO local_promotion_manual_event(manual_event_id,tenant_id,authorization_id,event_sequence,state,command_id,request_sha256,quote_id,store_id,terminal_id,action_type,source_line_id,amount_or_rate,payment_method,before_fingerprint,preview_fingerprint,incremental_discount_minor,package_version,policy_version_id,policy_sha256,operator_user_id,approver_user_id,business_date,reason_code,reason_text,correlation_id,result_json,result_sha256,occurred_at) VALUES(?,?,?,1,\'APPLIED\',?,?,?,?,?,\'ORDER_AMOUNT_OFF\',NULL,\'50\',\'CASH\',?,?,50,1,1,?,\'101\',\'102\',\'2026-08-17\',\'CUSTOMER_CARE\',\'synthetic approved adjustment\',?,\'{}\',?,?)',
        [
          '01K5M000000000000000000001',
          _binding.tenantId,
          '01K5M000000000000000000002',
          '01K5M000000000000000000003',
          _hash('m'),
          '01K5Q000000000000000000001',
          _binding.storeId,
          _binding.terminalId,
          _hash('a'),
          _hash('b'),
          _hash('p'),
          '01K5M000000000000000000004',
          _hash('r'),
          '2026-08-17T05:59:30Z',
        ],
      );
    }
  }

  int count(String table) =>
      db.database.select('SELECT COUNT(*) c FROM $table').single['c']! as int;

  Object? scalar(String sql, [List<Object?> parameters = const []]) =>
      db.database.select(sql, parameters).single.values.first;

  void close() => db.close();
}

String _hash(String character) => List.filled(64, character).join();
