import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/member_cache_store.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: 'Synthetic Cashier',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  final now = DateTime.utc(2026, 8, 17, 10);

  test('stores only token hash and returns minimal unexpired masked view', () {
    final database = PosLocalDatabase.inMemory(binding);
    addTearDown(database.close);
    final store = MemberCacheStore(database);
    store.upsert(entry(now));

    final view = store.resolve('synthetic-member-token-a', now);
    expect(view?.memberRef, '01K5C000000000000000000001');
    expect(view?.maskedLabel, '会员-000001');
    expect(view?.levelCode, 'BASIC');
    final stored = database.database
        .select('SELECT member_token_hash FROM local_member_cache')
        .single['member_token_hash'];
    expect(stored, isNot('synthetic-member-token-a'));

    final columns = database.database
        .select('PRAGMA table_info(local_member_cache)')
        .map((row) => row['name'] as String)
        .toSet();
    expect(columns, isNot(contains('mobile')));
    expect(columns, isNot(contains('card_no')));
    expect(columns, isNot(contains('open_id')));
    expect(columns, isNot(contains('points_balance')));
  });

  test('rejects cross-tenant cache and stale snapshot cannot overwrite', () {
    final database = PosLocalDatabase.inMemory(binding);
    addTearDown(database.close);
    final store = MemberCacheStore(database);
    expect(
      () => store.upsert(entry(now, tenantId: 'TENANT_B')),
      throwsStateError,
    );
    store.upsert(entry(now, snapshotVersion: 2, maskedLabel: '会员-新版'));
    store.upsert(entry(now, snapshotVersion: 1, maskedLabel: '会员-旧版'));
    expect(
      store.resolve('synthetic-member-token-a', now)?.maskedLabel,
      '会员-新版',
    );
  });

  test('revocation and expiry fail closed then purge safely', () {
    final database = PosLocalDatabase.inMemory(binding);
    addTearDown(database.close);
    final store = MemberCacheStore(database);
    store.upsert(entry(now));
    store.revoke('01K5C000000000000000000001', now);
    expect(store.resolve('synthetic-member-token-a', now), isNull);
    expect(store.purge(now), 1);

    store.upsert(entry(now, expiresAt: now.add(const Duration(seconds: 1))));
    expect(
      store.resolve(
        'synthetic-member-token-a',
        now.add(const Duration(seconds: 2)),
      ),
      isNull,
    );
    expect(store.purge(now.add(const Duration(seconds: 2))), 1);
  });

  test('schema is v14 and checksum protects every released migration', () {
    final database = PosLocalDatabase.inMemory(binding);
    addTearDown(database.close);
    expect(
      database.database.select('PRAGMA user_version').single.values.first,
      14,
    );
    expect(
      database.database
          .select('SELECT COUNT(*) value FROM local_schema_history')
          .single['value'],
      14,
    );
  });
}

MemberCacheEntry entry(
  DateTime now, {
  String tenantId = 'TENANT_A',
  int snapshotVersion = 1,
  String maskedLabel = '会员-000001',
  DateTime? expiresAt,
}) => MemberCacheEntry(
  tenantId: tenantId,
  storeId: '1101',
  memberRef: '01K5C000000000000000000001',
  memberToken: 'synthetic-member-token-a',
  maskedLabel: maskedLabel,
  levelCode: 'BASIC',
  rightsDigest: List.filled(64, 'a').join(),
  snapshotVersion: snapshotVersion,
  expiresAt: expiresAt ?? now.add(const Duration(hours: 1)),
  receivedAt: now,
);
