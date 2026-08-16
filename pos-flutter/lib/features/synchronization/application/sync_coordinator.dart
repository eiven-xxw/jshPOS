import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:sqlite3/sqlite3.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../checkout/domain/checkout_models.dart';
import '../../checkout/domain/ulid_generator.dart';
import '../domain/sync_models.dart';

typedef SyncFailureInjector = void Function(String checkpoint);

abstract interface class LocalSyncChangeApplier {
  void apply(Database database, TrustedDeviceBinding binding, SyncChange change);
}

final class SyncControlChangeApplier implements LocalSyncChangeApplier {
  const SyncControlChangeApplier();

  @override
  void apply(
    Database database,
    TrustedDeviceBinding binding,
    SyncChange change,
  ) {
    if (change.eventType != 'sync.device-policy.changed.v1') {
      throw StateError(
        'SYNC_CHANGE_UNSUPPORTED: ${change.eventType} is not admitted',
      );
    }
    final status = change.payload['deviceStatus'];
    final minProtocol = change.payload['minProtocolVersion'];
    final maxProtocol = change.payload['maxProtocolVersion'];
    final policyVersion = change.payload['policyVersion'];
    if (!const {'ACTIVE', 'BLOCKED', 'REVOKED'}.contains(status) ||
        minProtocol is! String ||
        maxProtocol is! String ||
        policyVersion is! int ||
        policyVersion <= 0) {
      throw StateError('SYNC_CHANGE_INVALID: device policy payload is invalid');
    }
    database.execute(
      'INSERT INTO local_sync_control(singleton_id,tenant_id,device_status,min_protocol_version,max_protocol_version,policy_version,updated_at) VALUES(1,?,?,?,?,?,?) ON CONFLICT(singleton_id) DO UPDATE SET device_status=excluded.device_status,min_protocol_version=excluded.min_protocol_version,max_protocol_version=excluded.max_protocol_version,policy_version=excluded.policy_version,updated_at=excluded.updated_at WHERE excluded.tenant_id=local_sync_control.tenant_id AND excluded.policy_version>local_sync_control.policy_version',
      [
        binding.tenantId,
        status,
        minProtocol,
        maxProtocol,
        policyVersion,
        change.publishedAt.toUtc().toIso8601String(),
      ],
    );
  }
}

final class PosSyncCoordinator {
  PosSyncCoordinator({
    required this.localDatabase,
    required this.transport,
    required this.ulids,
    required this.changeApplier,
    DateTime Function()? now,
    this.streams = const ['sync.control'],
    this.maxAttempts = 12,
    this.backlogAlertThreshold = 1000,
    this.leaseDuration = const Duration(seconds: 30),
    this.failureInjector,
  }) : _now = now ?? DateTime.now;

  static const int _maxBatchEvents = 100;
  static const int _maxBatchBytes = 2 * 1024 * 1024;
  static const int _maxEventBytes = 256 * 1024;

  final PosLocalDatabase localDatabase;
  final PosSyncTransport transport;
  final UlidGenerator ulids;
  final LocalSyncChangeApplier changeApplier;
  final DateTime Function() _now;
  final List<String> streams;
  final int maxAttempts;
  final int backlogAlertThreshold;
  final Duration leaseDuration;
  final SyncFailureInjector? failureInjector;

  Database get _db => localDatabase.database;
  TrustedDeviceBinding get _binding => localDatabase.binding;

  Future<SyncRunSummary> runOnce() async {
    var claimed = 0;
    var acked = 0;
    var retrying = 0;
    var deadLetters = 0;
    var pulled = 0;
    var applied = 0;
    String? errorCode;
    try {
      final bootstrap = await transport.bootstrap(ulids.next());
      _requireBootstrapBinding(bootstrap);
      _recoverExpiredLeases();
      await _resolvePendingResults();
      final batch = _claimBatch(bootstrap);
      claimed = batch?.events.length ?? 0;
      if (batch != null) {
        try {
          final response = await transport.push(batch);
          final counts = _applyPushResponse(batch, response);
          acked += counts.acked;
          retrying += counts.retrying;
          deadLetters += counts.deadLetters;
        } on SyncTransportException catch (failure) {
          final counts = _failClaimedBatch(batch, failure);
          retrying += counts.retrying;
          deadLetters += counts.deadLetters;
          errorCode ??= failure.code;
        }
      }
      for (final stream in streams) {
        final pullResult = await _pullStream(stream);
        pulled += pullResult.pulled;
        applied += pullResult.applied;
        deadLetters += pullResult.deadLetters;
        errorCode ??= pullResult.errorCode;
      }
    } on SyncTransportException catch (failure) {
      errorCode = failure.code;
    } on Object catch (failure) {
      errorCode = 'SYNC_RUN_FAILED_${failure.runtimeType.toString().toUpperCase()}';
    }
    final backlog = _refreshBacklogAlert();
    return SyncRunSummary(
      claimed: claimed,
      acked: acked,
      retrying: retrying,
      deadLetters: deadLetters,
      pulled: pulled,
      applied: applied,
      backlog: backlog,
      errorCode: errorCode,
    );
  }

  void _requireBootstrapBinding(SyncBootstrap bootstrap) {
    if (bootstrap.protocolVersion != '1.0' ||
        bootstrap.deviceId != _binding.terminalId ||
        bootstrap.terminalId != _binding.terminalId ||
        bootstrap.storeId != _binding.storeId ||
        bootstrap.maxBatchEvents < 1 ||
        bootstrap.maxBatchBytes < _maxBatchBytes ||
        bootstrap.maxEventBytes < _maxEventBytes) {
      throw const SyncTransportException(
        'SYNC_BOOTSTRAP_BINDING_MISMATCH',
        'server bootstrap does not match the trusted local binding',
        retryable: false,
      );
    }
  }

  void _recoverExpiredLeases() {
    final now = _utcNow();
    _db.execute(
      'UPDATE local_outbox SET status=\'RETRY\',lease_token=NULL,lease_until=NULL,next_attempt_at=?,last_error_code=\'LEASE_EXPIRED_AFTER_RESTART\',updated_at=? WHERE tenant_id=? AND status=\'SENDING\' AND lease_until<=?',
      [now, now, _binding.tenantId, now],
    );
  }

  Future<void> _resolvePendingResults() async {
    final rows = _db.select(
      'SELECT * FROM local_outbox WHERE tenant_id=? AND status=\'RETRY\' AND last_ack_status=\'ACCEPTED_PENDING\' ORDER BY device_sequence LIMIT 100',
      [_binding.tenantId],
    );
    for (final row in rows) {
      try {
        final ack = await transport.result(
          row['event_id']! as String,
          row['correlation_id']! as String,
        );
        _applyAckById(row['event_id']! as String, row['payload_sha256']! as String,
            (row['attempt_count']! as int), ack);
      } on SyncTransportException {
        return;
      }
    }
  }

  SyncPushBatch? _claimBatch(SyncBootstrap bootstrap) {
    final now = _utcNow();
    final batchId = ulids.next();
    final events = <SyncEventEnvelope>[];
    var batchBytes = 64;
    localDatabase.transaction(() {
      final rows = _db.select(
        'SELECT * FROM local_outbox WHERE tenant_id=? AND status IN (\'PENDING\',\'RETRY\') AND (next_attempt_at IS NULL OR next_attempt_at<=?) ORDER BY device_sequence LIMIT 100',
        [_binding.tenantId, now],
      );
      for (final row in rows) {
        SyncEventEnvelope event;
        try {
          event = _eventFromRow(row);
        } on StateError catch (failure) {
          _moveOutboxToDeadLetter(
            row['event_id']! as String,
            row['attempt_count']! as int,
            'LOCAL_EVENT_INVALID',
            failure.message.toString(),
          );
          continue;
        }
        final eventBytes = utf8.encode(jsonEncode(event.toJson())).length;
        final eventLimit = bootstrap.maxEventBytes < _maxEventBytes
            ? bootstrap.maxEventBytes
            : _maxEventBytes;
        if (eventBytes > eventLimit) {
          _moveOutboxToDeadLetter(
            event.eventId,
            event.attemptCount,
            'EVENT_TOO_LARGE',
            'serialized event exceeds the negotiated 256 KiB limit',
          );
          continue;
        }
        final batchLimit = bootstrap.maxBatchBytes < _maxBatchBytes
            ? bootstrap.maxBatchBytes
            : _maxBatchBytes;
        if (events.isNotEmpty && batchBytes + eventBytes > batchLimit) break;
        events.add(event);
        batchBytes += eventBytes;
        if (events.length >= bootstrap.maxBatchEvents ||
            events.length >= _maxBatchEvents) {
          break;
        }
      }
      final leaseUntil = _now()
          .toUtc()
          .add(leaseDuration)
          .toIso8601String();
      for (final event in events) {
        _db.execute(
          'UPDATE local_outbox SET status=\'SENDING\',lease_token=?,lease_until=?,attempt_count=attempt_count+1,last_error_code=NULL,updated_at=? WHERE tenant_id=? AND event_id=? AND status IN (\'PENDING\',\'RETRY\')',
          [batchId, leaseUntil, now, _binding.tenantId, event.eventId],
        );
        if (_db.updatedRows != 1) {
          throw StateError('SYNC_OUTBOX_CLAIM_CONFLICT: ${event.eventId}');
        }
      }
    });
    return events.isEmpty ? null : SyncPushBatch(batchId: batchId, events: events);
  }

  SyncEventEnvelope _eventFromRow(Row row) {
    final payloadJson = row['payload_json']! as String;
    final payloadHash = sha256.convert(utf8.encode(payloadJson)).toString();
    if (payloadHash != row['payload_sha256']) {
      throw StateError(
        'SYNC_LOCAL_PAYLOAD_TAMPERED: ${row['event_id']}',
      );
    }
    final eventType = row['event_type']! as String;
    final versionMatch = RegExp(r'\.v([1-9][0-9]*)$').firstMatch(eventType);
    if (versionMatch == null) {
      throw StateError('SYNC_EVENT_VERSION_INVALID: $eventType');
    }
    return SyncEventEnvelope(
      eventId: row['event_id']! as String,
      stream: row['stream_code']! as String,
      eventType: eventType,
      eventVersion: int.parse(versionMatch.group(1)!),
      aggregateId: row['aggregate_id']! as String,
      aggregateVersion: row['aggregate_version']! as int,
      deviceId: _binding.terminalId,
      storeId: _binding.storeId,
      terminalId: _binding.terminalId,
      sequenceNo: row['device_sequence']! as int,
      occurredAt: DateTime.parse(row['created_at']! as String).toUtc(),
      idempotencyKey: row['event_id']! as String,
      correlationId: row['correlation_id']! as String,
      payloadHash: payloadHash,
      payload: decodeObject(payloadJson),
      attemptCount: (row['attempt_count']! as int) + 1,
    );
  }

  ({int acked, int retrying, int deadLetters}) _applyPushResponse(
    SyncPushBatch batch,
    SyncPushResponse response,
  ) {
    if (response.batchId != batch.batchId) {
      return _failClaimedBatch(
        batch,
        const SyncTransportException(
          'SYNC_BATCH_ACK_MISMATCH',
          'response batch identity differs',
          retryable: false,
        ),
      );
    }
    final byId = <String, SyncEventAck>{};
    for (final ack in response.acks) {
      if (byId.putIfAbsent(ack.eventId, () => ack) != ack) {
        return _failClaimedBatch(
          batch,
          const SyncTransportException(
            'SYNC_DUPLICATE_ACK',
            'response contains duplicate event ACKs',
            retryable: false,
          ),
        );
      }
    }
    var acked = 0;
    var retrying = 0;
    var deadLetters = 0;
    for (final event in batch.events) {
      final ack = byId[event.eventId];
      if (ack == null) {
        _retryOutbox(event.eventId, event.attemptCount, 'ACK_MISSING');
        retrying++;
        continue;
      }
      final result = _applyAckById(
        event.eventId,
        event.payloadHash,
        event.attemptCount,
        ack,
      );
      acked += result == 'ACKED' ? 1 : 0;
      retrying += result == 'RETRY' ? 1 : 0;
      deadLetters += result == 'DEAD_LETTER' ? 1 : 0;
    }
    return (acked: acked, retrying: retrying, deadLetters: deadLetters);
  }

  String _applyAckById(
    String eventId,
    String expectedPayloadHash,
    int attemptCount,
    SyncEventAck ack,
  ) {
    if (ack.eventId != eventId || ack.payloadHash != expectedPayloadHash) {
      _moveOutboxToDeadLetter(
        eventId,
        attemptCount,
        'ACK_EVIDENCE_MISMATCH',
        'server ACK identity or payload hash differs',
      );
      return 'DEAD_LETTER';
    }
    final now = _utcNow();
    switch (ack.status) {
      case 'ACCEPTED':
      case 'DUPLICATE':
        _db.execute(
          'UPDATE local_outbox SET status=\'ACKED\',lease_token=NULL,lease_until=NULL,next_attempt_at=NULL,last_ack_status=?,last_error_code=NULL,acked_at=?,updated_at=? WHERE tenant_id=? AND event_id=? AND status IN (\'SENDING\',\'RETRY\')',
          [ack.status, now, now, _binding.tenantId, eventId],
        );
        return 'ACKED';
      case 'ACCEPTED_PENDING':
      case 'REJECTED_RETRYABLE':
        _retryOutbox(
          eventId,
          attemptCount,
          ack.resultCode ?? ack.status,
          lastAckStatus: ack.status,
          retryAfterMs: ack.retryAfterMs,
        );
        return 'RETRY';
      case 'CONFLICT':
      case 'REJECTED_FINAL':
      case 'DEVICE_BLOCKED':
        _moveOutboxToDeadLetter(
          eventId,
          attemptCount,
          ack.resultCode ?? ack.status,
          'server returned ${ack.status}',
        );
        if (ack.status == 'DEVICE_BLOCKED') _markLocalDeviceBlocked(now);
        return 'DEAD_LETTER';
      default:
        _moveOutboxToDeadLetter(
          eventId,
          attemptCount,
          'ACK_STATUS_UNKNOWN',
          'unrecognized server ACK status',
        );
        return 'DEAD_LETTER';
    }
  }

  ({int retrying, int deadLetters}) _failClaimedBatch(
    SyncPushBatch batch,
    SyncTransportException failure,
  ) {
    var retrying = 0;
    var deadLetters = 0;
    localDatabase.transaction(() {
      for (final event in batch.events) {
        if (!failure.retryable || event.attemptCount >= maxAttempts) {
          _moveOutboxToDeadLetter(
            event.eventId,
            event.attemptCount,
            failure.code,
            'transport failure exhausted or is final',
          );
          deadLetters++;
        } else {
          _retryOutbox(event.eventId, event.attemptCount, failure.code);
          retrying++;
        }
      }
    });
    return (retrying: retrying, deadLetters: deadLetters);
  }

  void _retryOutbox(
    String eventId,
    int attemptCount,
    String errorCode, {
    String? lastAckStatus,
    int? retryAfterMs,
  }) {
    if (attemptCount >= maxAttempts) {
      _moveOutboxToDeadLetter(
        eventId,
        attemptCount,
        'RETRY_BUDGET_EXHAUSTED',
        errorCode,
      );
      return;
    }
    final delay = retryAfterMs ?? _retryDelayMs(eventId, attemptCount);
    final now = _now().toUtc();
    _db.execute(
      'UPDATE local_outbox SET status=\'RETRY\',lease_token=NULL,lease_until=NULL,next_attempt_at=?,last_error_code=?,last_ack_status=?,updated_at=? WHERE tenant_id=? AND event_id=? AND status IN (\'SENDING\',\'RETRY\')',
      [
        now.add(Duration(milliseconds: delay)).toIso8601String(),
        errorCode,
        lastAckStatus,
        now.toIso8601String(),
        _binding.tenantId,
        eventId,
      ],
    );
  }

  int _retryDelayMs(String eventId, int attempt) {
    final exponent = attempt.clamp(0, 7).toInt();
    final base = (500 * (1 << exponent)).clamp(500, 60000).toInt();
    final jitter = eventId.codeUnits.fold<int>(0, (sum, value) => sum + value) % 251;
    return base + jitter;
  }

  void _moveOutboxToDeadLetter(
    String eventId,
    int attempts,
    String failureCode,
    String summary,
  ) {
    final now = _utcNow();
    _db.execute(
      'UPDATE local_outbox SET status=\'FINAL_REJECTED\',lease_token=NULL,lease_until=NULL,next_attempt_at=NULL,last_error_code=?,updated_at=? WHERE tenant_id=? AND event_id=? AND status<>\'ACKED\'',
      [failureCode, now, _binding.tenantId, eventId],
    );
    _db.execute(
      'INSERT INTO local_sync_dead_letter(dead_letter_id,tenant_id,direction,source_id,failure_code,failure_summary,status,attempt_count,created_at) VALUES(?,?,\'OUTBOUND\',?,?,?,\'OPEN\',?,?) ON CONFLICT(tenant_id,direction,source_id) DO UPDATE SET failure_code=excluded.failure_code,failure_summary=excluded.failure_summary,attempt_count=excluded.attempt_count',
      [
        ulids.next(),
        _binding.tenantId,
        eventId,
        failureCode,
        summary.length > 512 ? summary.substring(0, 512) : summary,
        attempts,
        now,
      ],
    );
  }

  Future<({int pulled, int applied, int deadLetters, String? errorCode})>
  _pullStream(String stream) async {
    if (!await _flushPendingAck(stream)) {
      return (pulled: 0, applied: 0, deadLetters: 0, errorCode: 'SYNC_ACK_PENDING');
    }
    final cursorRows = _db.select(
      'SELECT applied_cursor FROM local_sync_cursor WHERE tenant_id=? AND stream_code=?',
      [_binding.tenantId, stream],
    );
    final cursor = cursorRows.isEmpty
        ? null
        : cursorRows.single['applied_cursor'] as String?;
    SyncPullPage page;
    try {
      page = await transport.pull(
        stream: stream,
        cursor: cursor,
        correlationId: ulids.next(),
      );
    } on SyncTransportException catch (failure) {
      return (pulled: 0, applied: 0, deadLetters: 0, errorCode: failure.code);
    }
    if (page.stream != stream ||
        page.pageDigest != _pageDigest(page.changes)) {
      if (page.changes.isNotEmpty) {
        _recordInboundDeadLetter(
          page.changes.first.changeId,
          'PULL_PAGE_DIGEST_MISMATCH',
          'server page digest or stream differs',
        );
      }
      return (
        pulled: page.changes.length,
        applied: 0,
        deadLetters: page.changes.isEmpty ? 0 : 1,
        errorCode: 'PULL_PAGE_DIGEST_MISMATCH',
      );
    }
    if (page.changes.isEmpty) {
      return (pulled: 0, applied: 0, deadLetters: 0, errorCode: null);
    }
    try {
      localDatabase.transaction(() {
        for (final change in page.changes) {
          _applyInboundChange(stream, page.nextCursor, change);
          _db.execute(
            'UPDATE local_sync_dead_letter SET status=\'RESOLVED\',resolved_at=? WHERE tenant_id=? AND direction=\'INBOUND\' AND source_id=? AND status IN (\'OPEN\',\'RETRYING\')',
            [_utcNow(), _binding.tenantId, change.changeId],
          );
        }
        failureInjector?.call('inbox.before-cursor');
        final idsJson = jsonEncode(
          page.changes.map((change) => change.changeId).toList(),
        );
        _db.execute(
          'INSERT INTO local_sync_cursor(tenant_id,stream_code,applied_cursor,applied_page_sha256,applied_change_ids_json,remote_acked_cursor,ack_retry_count,last_error_code,updated_at) VALUES(?,?,?,?,?,NULL,0,NULL,?) ON CONFLICT(tenant_id,stream_code) DO UPDATE SET applied_cursor=excluded.applied_cursor,applied_page_sha256=excluded.applied_page_sha256,applied_change_ids_json=excluded.applied_change_ids_json,ack_retry_count=0,last_error_code=NULL,updated_at=excluded.updated_at',
          [
            _binding.tenantId,
            stream,
            page.nextCursor,
            page.pageDigest,
            idsJson,
            _utcNow(),
          ],
        );
        failureInjector?.call('inbox.after-cursor');
      });
    } on Object catch (failure) {
      _recordInboundDeadLetter(
        page.changes.first.changeId,
        'INBOUND_APPLY_FAILED',
        failure.runtimeType.toString(),
      );
      return (
        pulled: page.changes.length,
        applied: 0,
        deadLetters: 1,
        errorCode: 'INBOUND_APPLY_FAILED',
      );
    }
    final acknowledged = await _flushPendingAck(stream);
    return (
      pulled: page.changes.length,
      applied: page.changes.length,
      deadLetters: 0,
      errorCode: acknowledged ? null : 'SYNC_ACK_PENDING',
    );
  }

  void _applyInboundChange(
    String stream,
    String pageCursor,
    SyncChange change,
  ) {
    final payloadJson = jsonEncode(change.payload);
    if (sha256.convert(utf8.encode(payloadJson)).toString() !=
        change.payloadHash) {
      throw StateError('SYNC_INBOUND_HASH_MISMATCH: ${change.changeId}');
    }
    final existing = _db.select(
      'SELECT payload_sha256,status FROM local_inbox WHERE tenant_id=? AND change_id=?',
      [_binding.tenantId, change.changeId],
    );
    if (existing.isNotEmpty) {
      if (existing.single['payload_sha256'] != change.payloadHash) {
        throw StateError('SYNC_INBOUND_ID_HASH_CONFLICT: ${change.changeId}');
      }
      return;
    }
    final effect = _db.select(
      'SELECT change_id,payload_sha256 FROM local_inbox WHERE tenant_id=? AND stream_code=? AND aggregate_id=? AND aggregate_version=? AND event_type=?',
      [
        _binding.tenantId,
        stream,
        change.aggregateId,
        change.aggregateVersion,
        change.eventType,
      ],
    );
    if (effect.isNotEmpty) {
      throw StateError(
        'SYNC_INBOUND_AGGREGATE_CONFLICT: ${change.aggregateId}',
      );
    }
    final now = _utcNow();
    _db.execute(
      'INSERT INTO local_inbox(change_id,tenant_id,stream_code,event_type,aggregate_id,aggregate_version,payload_json,payload_sha256,page_cursor,status,received_at) VALUES(?,?,?,?,?,?,?,?,?,\'RECEIVED\',?)',
      [
        change.changeId,
        _binding.tenantId,
        stream,
        change.eventType,
        change.aggregateId,
        change.aggregateVersion,
        payloadJson,
        change.payloadHash,
        pageCursor,
        now,
      ],
    );
    failureInjector?.call('inbox.received');
    changeApplier.apply(_db, _binding, change);
    _db.execute(
      'UPDATE local_inbox SET status=\'APPLIED\',applied_at=? WHERE tenant_id=? AND change_id=? AND status=\'RECEIVED\'',
      [now, _binding.tenantId, change.changeId],
    );
  }

  Future<bool> _flushPendingAck(String stream) async {
    final rows = _db.select(
      'SELECT * FROM local_sync_cursor WHERE tenant_id=? AND stream_code=? AND applied_cursor IS NOT NULL AND (remote_acked_cursor IS NULL OR remote_acked_cursor<>applied_cursor)',
      [_binding.tenantId, stream],
    );
    if (rows.isEmpty) return true;
    final row = rows.single;
    final changeIds = (jsonDecode(row['applied_change_ids_json']! as String)
            as List<Object?>)
        .cast<String>();
    try {
      await transport.acknowledge(
        SyncAckCommand(
          stream: stream,
          cursor: row['applied_cursor']! as String,
          appliedChangeIds: changeIds,
          pageDigest: row['applied_page_sha256']! as String,
        ),
        ulids.next(),
      );
      _db.execute(
        'UPDATE local_sync_cursor SET remote_acked_cursor=applied_cursor,ack_retry_count=0,last_error_code=NULL,updated_at=? WHERE tenant_id=? AND stream_code=? AND applied_cursor=?',
        [
          _utcNow(),
          _binding.tenantId,
          stream,
          row['applied_cursor'],
        ],
      );
      return true;
    } on SyncTransportException catch (failure) {
      _db.execute(
        'UPDATE local_sync_cursor SET ack_retry_count=ack_retry_count+1,last_error_code=?,updated_at=? WHERE tenant_id=? AND stream_code=?',
        [failure.code, _utcNow(), _binding.tenantId, stream],
      );
      return false;
    }
  }

  void _recordInboundDeadLetter(
    String sourceId,
    String code,
    String summary,
  ) {
    _db.execute(
      'INSERT INTO local_sync_dead_letter(dead_letter_id,tenant_id,direction,source_id,failure_code,failure_summary,status,attempt_count,created_at) VALUES(?,?,\'INBOUND\',?,?,?,\'OPEN\',1,?) ON CONFLICT(tenant_id,direction,source_id) DO UPDATE SET failure_code=excluded.failure_code,failure_summary=excluded.failure_summary,attempt_count=local_sync_dead_letter.attempt_count+1',
      [
        ulids.next(),
        _binding.tenantId,
        sourceId,
        code,
        summary.length > 512 ? summary.substring(0, 512) : summary,
        _utcNow(),
      ],
    );
  }

  void _markLocalDeviceBlocked(String now) {
    _db.execute(
      'INSERT INTO local_sync_control(singleton_id,tenant_id,device_status,min_protocol_version,max_protocol_version,policy_version,updated_at) VALUES(1,?,\'BLOCKED\',\'1.0\',\'1.0\',1,?) ON CONFLICT(singleton_id) DO UPDATE SET device_status=\'BLOCKED\',policy_version=local_sync_control.policy_version+1,updated_at=excluded.updated_at WHERE excluded.tenant_id=local_sync_control.tenant_id',
      [_binding.tenantId, now],
    );
  }

  int _refreshBacklogAlert() {
    final backlog = _db
        .select(
          'SELECT COUNT(*) value FROM local_outbox WHERE tenant_id=? AND status IN (\'PENDING\',\'SENDING\',\'RETRY\')',
          [_binding.tenantId],
        )
        .single['value']! as int;
    final now = _utcNow();
    final status = backlog >= backlogAlertThreshold ? 'OPEN' : 'RESOLVED';
    _db.execute(
      'INSERT INTO local_sync_alert(tenant_id,alert_code,status,observed_value,threshold_value,first_seen_at,updated_at) VALUES(?,\'OUTBOX_BACKLOG\',?,?,?,?,?) ON CONFLICT(tenant_id,alert_code) DO UPDATE SET status=excluded.status,observed_value=excluded.observed_value,threshold_value=excluded.threshold_value,updated_at=excluded.updated_at',
      [
        _binding.tenantId,
        status,
        backlog,
        backlogAlertThreshold,
        now,
        now,
      ],
    );
    return backlog;
  }

  String _pageDigest(List<SyncChange> changes) => sha256
      .convert(utf8.encode(syncPageDigest(changes)))
      .toString();

  String _utcNow() => _now().toUtc().toIso8601String();
}
