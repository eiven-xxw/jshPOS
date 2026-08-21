import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/checkout/domain/checkout_models.dart';
import 'package:jshpos_pos/features/checkout/domain/ulid_generator.dart';
import 'package:jshpos_pos/features/synchronization/application/sync_coordinator.dart';
import 'package:jshpos_pos/features/synchronization/domain/sync_models.dart';
import 'package:jshpos_pos/infrastructure/local_database/pos_local_database.dart';

const binding = TrustedDeviceBinding(
  tenantId: 'TENANT_A',
  storeId: '1101',
  deviceId: '01K2A000000000000000000009',
  terminalId: '01K2A000000000000000000011',
  cashierId: '101',
  cashierName: 'Synthetic Alice',
  storeTimezone: 'Asia/Shanghai',
);

void main() {
  test('SQLite V8 preserves Sprint 3 sync while avoiding T1 probe tables', () {
    final fixture = SyncFixture();
    addTearDown(fixture.close);
    expect(
      fixture.db.database.select('PRAGMA user_version').single.values.first,
      8,
    );
    expect(fixture.count('local_schema_history'), 8);
    expect(fixture.count('local_inbox'), 0);
    expect(
      fixture.db.database
          .select(
            "SELECT COUNT(*) value FROM sqlite_master WHERE name LIKE 'syn_%'",
          )
          .single['value'],
      0,
    );
  });

  test(
    'ACK lost resends the original identity and converges as duplicate',
    () async {
      final fixture = SyncFixture();
      addTearDown(fixture.close);
      final eventId = fixture.enqueue();
      fixture.transport.pushFailures = 1;
      fixture.transport.duplicateAfterAcceptedUnknown = true;

      final first = await fixture.coordinator().runOnce();
      expect(first.retrying, 1);
      expect(fixture.status(eventId), 'RETRY');
      fixture.advance(const Duration(minutes: 2));

      final second = await fixture.coordinator().runOnce();
      expect(second.acked, 1);
      expect(fixture.status(eventId), 'ACKED');
      expect(fixture.transport.pushedEventIds, [eventId, eventId]);
      expect(fixture.transport.pushedDeviceIds, [
        binding.deviceId,
        binding.deviceId,
      ]);
    },
  );

  test(
    'ACCEPTED_PENDING resolves by result query without a new command',
    () async {
      final fixture = SyncFixture();
      addTearDown(fixture.close);
      final eventId = fixture.enqueue();
      fixture.transport.pendingEvents.add(eventId);

      final first = await fixture.coordinator().runOnce();
      expect(first.retrying, 1);
      expect(fixture.status(eventId), 'RETRY');
      fixture.advance(const Duration(minutes: 2));
      fixture.transport.pendingEvents.remove(eventId);
      fixture.transport.resultStatuses[eventId] = 'ACCEPTED';

      await fixture.coordinator().runOnce();
      expect(fixture.status(eventId), 'ACKED');
      expect(fixture.transport.pushedEventIds, [eventId]);
      expect(fixture.transport.resultQueries, [eventId]);
    },
  );

  test('expired SENDING lease is recovered after process restart', () async {
    final fixture = SyncFixture();
    addTearDown(fixture.close);
    final eventId = fixture.enqueue();
    fixture.db.database.execute(
      "UPDATE local_outbox SET status='SENDING',lease_token=?,lease_until=?,attempt_count=1 WHERE event_id=?",
      [
        fixture.ids.next(),
        fixture.now.subtract(const Duration(seconds: 1)).toIso8601String(),
        eventId,
      ],
    );

    final result = await fixture.coordinator().runOnce();
    expect(result.acked, 1);
    expect(fixture.status(eventId), 'ACKED');
  });

  test(
    'final conflict enters isolated dead letter and is never silent',
    () async {
      final fixture = SyncFixture();
      addTearDown(fixture.close);
      final eventId = fixture.enqueue();
      fixture.transport.finalStatuses[eventId] = 'CONFLICT';

      final result = await fixture.coordinator().runOnce();
      expect(result.deadLetters, 1);
      expect(fixture.status(eventId), 'FINAL_REJECTED');
      expect(fixture.count('local_sync_dead_letter'), 1);
    },
  );

  test(
    'tampered local payload is isolated without sending a command',
    () async {
      final fixture = SyncFixture();
      addTearDown(fixture.close);
      final eventId = fixture.enqueue();
      fixture.db.database.execute(
        'UPDATE local_outbox SET payload_json=? WHERE event_id=?',
        ['{"orderId":"tampered"}', eventId],
      );

      final result = await fixture.coordinator().runOnce();

      expect(result.claimed, 0);
      expect(fixture.status(eventId), 'FINAL_REJECTED');
      expect(fixture.transport.pushedEventIds, isEmpty);
      expect(fixture.count('local_sync_dead_letter'), 1);
    },
  );

  test(
    'downstream apply and cursor commit atomically then ACK retries',
    () async {
      final fixture = SyncFixture();
      addTearDown(fixture.close);
      fixture.transport.pullPage = fixture.controlPage();
      fixture.transport.ackFailures = 1;

      final first = await fixture.coordinator().runOnce();
      expect(first.applied, 1);
      expect(first.errorCode, 'SYNC_ACK_PENDING');
      expect(fixture.count('local_inbox'), 1);
      expect(fixture.count('local_sync_control'), 1);
      expect(
        fixture.db.database
            .select('SELECT remote_acked_cursor FROM local_sync_cursor')
            .single['remote_acked_cursor'],
        isNull,
      );

      fixture.transport.pullPage = fixture.emptyPage();
      final second = await fixture.coordinator().runOnce();
      expect(second.errorCode, isNull);
      expect(fixture.count('local_inbox'), 1);
      expect(fixture.transport.ackCalls, 2);
      expect(
        fixture.db.database
            .select('SELECT remote_acked_cursor FROM local_sync_cursor')
            .single['remote_acked_cursor'],
        isNotNull,
      );
    },
  );

  test('kill before cursor rolls back Inbox and business projection', () async {
    final fixture = SyncFixture();
    addTearDown(fixture.close);
    fixture.transport.pullPage = fixture.controlPage();
    var fail = true;
    final coordinator = fixture.coordinator(
      failureInjector: (checkpoint) {
        if (fail && checkpoint == 'inbox.before-cursor') {
          fail = false;
          throw StateError('fixed-seed-316008');
        }
      },
    );

    final first = await coordinator.runOnce();
    expect(first.errorCode, 'INBOUND_APPLY_FAILED');
    expect(fixture.count('local_inbox'), 0);
    expect(fixture.count('local_sync_control'), 0);
    expect(fixture.count('local_sync_dead_letter'), 1);

    final second = await coordinator.runOnce();
    expect(second.applied, 1);
    expect(fixture.count('local_inbox'), 1);
    expect(fixture.count('local_sync_control'), 1);
    expect(
      fixture.db.database
          .select('SELECT status FROM local_sync_dead_letter')
          .single['status'],
      'RESOLVED',
    );
  });

  test(
    'tampered downstream page is refused before cursor advancement',
    () async {
      final fixture = SyncFixture();
      addTearDown(fixture.close);
      final valid = fixture.controlPage();
      fixture.transport.pullPage = SyncPullPage(
        stream: valid.stream,
        changes: valid.changes,
        nextCursor: valid.nextCursor,
        pageDigest: List.filled(64, 'f').join(),
        hasMore: false,
      );

      final result = await fixture.coordinator().runOnce();
      expect(result.errorCode, 'PULL_PAGE_DIGEST_MISMATCH');
      expect(fixture.count('local_inbox'), 0);
      expect(fixture.count('local_sync_dead_letter'), 1);
    },
  );

  test('retry budget exhaustion creates an outbound dead letter', () async {
    final fixture = SyncFixture();
    addTearDown(fixture.close);
    final eventId = fixture.enqueue();
    fixture.db.database.execute(
      'UPDATE local_outbox SET attempt_count=11 WHERE event_id=?',
      [eventId],
    );
    fixture.transport.pushFailures = 1;

    final result = await fixture.coordinator().runOnce();
    expect(result.deadLetters, 1);
    expect(fixture.status(eventId), 'FINAL_REJECTED');
  });

  test(
    'backlog threshold opens and later resolves an operational alert',
    () async {
      final fixture = SyncFixture();
      addTearDown(fixture.close);
      fixture.enqueue();
      fixture.transport.pushFailures = 1;
      final first = await fixture.coordinator(backlogThreshold: 1).runOnce();
      expect(first.backlog, 1);
      expect(
        fixture.db.database
            .select('SELECT status FROM local_sync_alert')
            .single['status'],
        'OPEN',
      );
      fixture.advance(const Duration(minutes: 2));
      await fixture.coordinator(backlogThreshold: 1).runOnce();
      expect(
        fixture.db.database
            .select('SELECT status FROM local_sync_alert')
            .single['status'],
        'RESOLVED',
      );
    },
  );
}

final class SyncFixture {
  SyncFixture()
    : now = DateTime.utc(2026, 8, 16, 8),
      ids = UlidGenerator(
        random: Random(316001),
        now: () => DateTime.utc(2026, 8, 16, 8),
      ),
      db = PosLocalDatabase.inMemory(binding),
      transport = FakeSyncTransport();

  DateTime now;
  final UlidGenerator ids;
  final PosLocalDatabase db;
  final FakeSyncTransport transport;

  PosSyncCoordinator coordinator({
    SyncFailureInjector? failureInjector,
    int backlogThreshold = 1000,
  }) => PosSyncCoordinator(
    localDatabase: db,
    transport: transport,
    ulids: ids,
    changeApplier: const SyncControlChangeApplier(),
    now: () => now,
    backlogAlertThreshold: backlogThreshold,
    failureInjector: failureInjector,
  );

  String enqueue({String? eventId}) {
    final id = eventId ?? ids.next();
    final payload = <String, Object?>{
      'orderId': '01K2A000000000000000000031',
      'shiftId': '01K2A000000000000000000021',
      'paymentId': '01K2A000000000000000000061',
      'businessDate': '2026-08-16',
      'currency': 'CNY',
      'receivableAmountMinor': 1299,
      'aggregateVersion': 4,
      'snapshotHash': 'sha256:${List.filled(64, 'a').join()}',
    };
    final payloadJson = jsonEncode(payload);
    db.database.execute(
      "INSERT INTO local_outbox(event_id,tenant_id,device_sequence,stream_code,event_type,aggregate_id,aggregate_version,correlation_id,payload_json,payload_sha256,status,attempt_count,created_at) VALUES(?,?,1,'order.command','order.completed.v1',?,4,?,?,?,?,0,?)",
      [
        id,
        binding.tenantId,
        '01K2A000000000000000000031',
        ids.next(),
        payloadJson,
        sha256.convert(utf8.encode(payloadJson)).toString(),
        'PENDING',
        now.toIso8601String(),
      ],
    );
    return id;
  }

  SyncPullPage controlPage() {
    final payload = <String, Object?>{
      'deviceStatus': 'ACTIVE',
      'minProtocolVersion': '1.0',
      'maxProtocolVersion': '1.0',
      'policyVersion': 2,
    };
    final payloadHash = sha256
        .convert(utf8.encode(jsonEncode(payload)))
        .toString();
    final change = SyncChange(
      changeId: '01K2A000000000000000000051',
      eventType: 'sync.device-policy.changed.v1',
      aggregateId: binding.terminalId,
      aggregateVersion: 2,
      payloadHash: payloadHash,
      payload: payload,
      publishedAt: now,
    );
    return SyncPullPage(
      stream: 'sync.control',
      changes: [change],
      nextCursor: '01K2A000000000000000000041',
      pageDigest: sha256
          .convert(utf8.encode('${change.changeId}:$payloadHash\n'))
          .toString(),
      hasMore: false,
    );
  }

  SyncPullPage emptyPage() => SyncPullPage(
    stream: 'sync.control',
    changes: const [],
    nextCursor: '01K2A000000000000000000042',
    pageDigest: sha256.convert(const <int>[]).toString(),
    hasMore: false,
  );

  int count(String table) =>
      db.database.select('SELECT COUNT(*) value FROM $table').single['value']!
          as int;

  String status(String eventId) =>
      db.database.select('SELECT status FROM local_outbox WHERE event_id=?', [
            eventId,
          ]).single['status']!
          as String;

  void advance(Duration duration) => now = now.add(duration);
  void close() => db.close();
}

final class FakeSyncTransport implements PosSyncTransport {
  int pushFailures = 0;
  int ackFailures = 0;
  int ackCalls = 0;
  bool duplicateAfterAcceptedUnknown = false;
  final List<String> pushedEventIds = [];
  final List<String> pushedDeviceIds = [];
  final List<String> resultQueries = [];
  final Set<String> pendingEvents = {};
  final Map<String, String> resultStatuses = {};
  final Map<String, String> finalStatuses = {};
  final Set<String> acceptedUnknown = {};
  SyncPullPage? pullPage;

  @override
  Future<SyncBootstrap> bootstrap(String correlationId) async =>
      const SyncBootstrap(
        deviceId: '01K2A000000000000000000009',
        storeId: '1101',
        terminalId: '01K2A000000000000000000011',
        protocolVersion: '1.0',
        maxBatchEvents: 100,
        maxBatchBytes: 2097152,
        maxEventBytes: 262144,
      );

  @override
  Future<SyncPushResponse> push(SyncPushBatch batch) async {
    pushedEventIds.addAll(batch.events.map((event) => event.eventId));
    pushedDeviceIds.addAll(batch.events.map((event) => event.deviceId));
    for (final event in batch.events) {
      _payloadHashes[event.eventId] = event.payloadHash;
    }
    if (pushFailures > 0) {
      pushFailures--;
      acceptedUnknown.addAll(batch.events.map((event) => event.eventId));
      throw const SyncTransportException(
        'SYNC_TIMEOUT',
        'server accepted but ACK was lost',
        retryable: true,
      );
    }
    final acks = batch.events.map((event) {
      final finalStatus = finalStatuses[event.eventId];
      if (finalStatus != null) {
        return SyncEventAck(
          eventId: event.eventId,
          payloadHash: event.payloadHash,
          status: finalStatus,
          resultCode: 'FIXED_SEED_CONFLICT',
        );
      }
      if (pendingEvents.contains(event.eventId)) {
        return SyncEventAck(
          eventId: event.eventId,
          payloadHash: event.payloadHash,
          status: 'ACCEPTED_PENDING',
          resultCode: 'SERVER_PROCESSING',
          retryAfterMs: 1000,
        );
      }
      return SyncEventAck(
        eventId: event.eventId,
        payloadHash: event.payloadHash,
        status:
            duplicateAfterAcceptedUnknown &&
                acceptedUnknown.contains(event.eventId)
            ? 'DUPLICATE'
            : 'ACCEPTED',
        resultCode: 'APPLIED',
      );
    }).toList();
    return SyncPushResponse(
      batchId: batch.batchId,
      acks: acks,
      serverTime: DateTime.utc(2026, 8, 16, 8),
    );
  }

  @override
  Future<SyncEventAck> result(String eventId, String correlationId) async {
    resultQueries.add(eventId);
    final status = resultStatuses[eventId] ?? 'ACCEPTED_PENDING';
    return SyncEventAck(
      eventId: eventId,
      payloadHash: acceptedPayloadHash(eventId),
      status: status,
      resultCode: status == 'ACCEPTED' ? 'APPLIED' : 'SERVER_PROCESSING',
    );
  }

  String acceptedPayloadHash(String eventId) {
    return _payloadHashes[eventId] ?? '';
  }

  final Map<String, String> _payloadHashes = {};

  @override
  Future<SyncPullPage> pull({
    required String stream,
    required String correlationId,
    String? cursor,
    int limit = 100,
  }) async =>
      pullPage ??
      SyncPullPage(
        stream: stream,
        changes: const [],
        nextCursor: '01K2A000000000000000000049',
        pageDigest: sha256.convert(const <int>[]).toString(),
        hasMore: false,
      );

  @override
  Future<void> acknowledge(SyncAckCommand command, String correlationId) async {
    ackCalls++;
    if (ackFailures > 0) {
      ackFailures--;
      throw const SyncTransportException(
        'SYNC_ACK_TIMEOUT',
        'ACK lost',
        retryable: true,
      );
    }
  }
}
