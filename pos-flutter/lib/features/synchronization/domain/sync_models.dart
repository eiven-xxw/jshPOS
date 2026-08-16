import 'dart:convert';

final class SyncTransportException implements Exception {
  const SyncTransportException(
    this.code,
    this.message, {
    required this.retryable,
    this.statusCode,
  });

  final String code;
  final String message;
  final bool retryable;
  final int? statusCode;

  @override
  String toString() => '$code: $message';
}

final class SyncEventEnvelope {
  const SyncEventEnvelope({
    required this.eventId,
    required this.stream,
    required this.eventType,
    required this.eventVersion,
    required this.aggregateId,
    required this.aggregateVersion,
    required this.deviceId,
    required this.storeId,
    required this.terminalId,
    required this.sequenceNo,
    required this.occurredAt,
    required this.idempotencyKey,
    required this.correlationId,
    required this.payloadHash,
    required this.payload,
    required this.attemptCount,
  });

  final String eventId;
  final String stream;
  final String eventType;
  final int eventVersion;
  final String aggregateId;
  final int aggregateVersion;
  final String deviceId;
  final String storeId;
  final String terminalId;
  final int sequenceNo;
  final DateTime occurredAt;
  final String idempotencyKey;
  final String correlationId;
  final String payloadHash;
  final Map<String, Object?> payload;
  final int attemptCount;

  Map<String, Object?> toJson() => {
    'eventId': eventId,
    'stream': stream,
    'eventType': eventType,
    'eventVersion': eventVersion,
    'aggregateId': aggregateId,
    'aggregateVersion': aggregateVersion,
    'deviceId': deviceId,
    'storeId': storeId,
    'terminalId': terminalId,
    'sequenceNo': sequenceNo,
    'occurredAt': occurredAt.toUtc().toIso8601String(),
    'idempotencyKey': idempotencyKey,
    'correlationId': correlationId,
    'payloadHash': payloadHash,
    'payload': payload,
  };
}

final class SyncPushBatch {
  const SyncPushBatch({required this.batchId, required this.events});
  final String batchId;
  final List<SyncEventEnvelope> events;

  Map<String, Object?> toJson() => {
    'protocolVersion': '1.0',
    'batchId': batchId,
    'events': events.map((event) => event.toJson()).toList(),
  };
}

final class SyncEventAck {
  const SyncEventAck({
    required this.eventId,
    required this.payloadHash,
    required this.status,
    this.resultCode,
    this.retryAfterMs,
  });

  factory SyncEventAck.fromJson(Map<String, Object?> json) => SyncEventAck(
    eventId: json['eventId']! as String,
    payloadHash: json['payloadHash']! as String,
    status: json['status']! as String,
    resultCode: json['resultCode'] as String?,
    retryAfterMs: (json['retryAfterMs'] as num?)?.toInt(),
  );

  final String eventId;
  final String payloadHash;
  final String status;
  final String? resultCode;
  final int? retryAfterMs;
}

final class SyncPushResponse {
  const SyncPushResponse({
    required this.batchId,
    required this.acks,
    required this.serverTime,
  });

  factory SyncPushResponse.fromJson(Map<String, Object?> json) =>
      SyncPushResponse(
        batchId: json['batchId']! as String,
        acks: (json['acks']! as List<Object?>)
            .map((item) => SyncEventAck.fromJson(item! as Map<String, Object?>))
            .toList(),
        serverTime: DateTime.parse(json['serverTime']! as String).toUtc(),
      );

  final String batchId;
  final List<SyncEventAck> acks;
  final DateTime serverTime;
}

final class SyncBootstrap {
  const SyncBootstrap({
    required this.deviceId,
    required this.storeId,
    required this.terminalId,
    required this.protocolVersion,
    required this.maxBatchEvents,
    required this.maxBatchBytes,
    required this.maxEventBytes,
  });

  factory SyncBootstrap.fromJson(Map<String, Object?> json) {
    final limits = json['limits']! as Map<String, Object?>;
    return SyncBootstrap(
      deviceId: json['deviceId']! as String,
      storeId: json['storeId']! as String,
      terminalId: json['terminalId']! as String,
      protocolVersion: json['protocolVersion']! as String,
      maxBatchEvents: (limits['maxBatchEvents']! as num).toInt(),
      maxBatchBytes: (limits['maxBatchBytes']! as num).toInt(),
      maxEventBytes: (limits['maxEventBytes']! as num).toInt(),
    );
  }

  final String deviceId;
  final String storeId;
  final String terminalId;
  final String protocolVersion;
  final int maxBatchEvents;
  final int maxBatchBytes;
  final int maxEventBytes;
}

final class SyncChange {
  const SyncChange({
    required this.changeId,
    required this.eventType,
    required this.aggregateId,
    required this.aggregateVersion,
    required this.payloadHash,
    required this.payload,
    required this.publishedAt,
  });

  factory SyncChange.fromJson(Map<String, Object?> json) => SyncChange(
    changeId: json['changeId']! as String,
    eventType: json['eventType']! as String,
    aggregateId: json['aggregateId']! as String,
    aggregateVersion: (json['aggregateVersion']! as num).toInt(),
    payloadHash: json['payloadHash']! as String,
    payload: json['payload']! as Map<String, Object?>,
    publishedAt: DateTime.parse(json['publishedAt']! as String).toUtc(),
  );

  final String changeId;
  final String eventType;
  final String aggregateId;
  final int aggregateVersion;
  final String payloadHash;
  final Map<String, Object?> payload;
  final DateTime publishedAt;
}

final class SyncPullPage {
  const SyncPullPage({
    required this.stream,
    required this.changes,
    required this.nextCursor,
    required this.pageDigest,
    required this.hasMore,
  });

  factory SyncPullPage.fromJson(Map<String, Object?> json) => SyncPullPage(
    stream: json['stream']! as String,
    changes: (json['changes']! as List<Object?>)
        .map((item) => SyncChange.fromJson(item! as Map<String, Object?>))
        .toList(),
    nextCursor: json['nextCursor']! as String,
    pageDigest: json['pageDigest']! as String,
    hasMore: json['hasMore']! as bool,
  );

  final String stream;
  final List<SyncChange> changes;
  final String nextCursor;
  final String pageDigest;
  final bool hasMore;
}

final class SyncAckCommand {
  const SyncAckCommand({
    required this.stream,
    required this.cursor,
    required this.appliedChangeIds,
    required this.pageDigest,
  });

  final String stream;
  final String cursor;
  final List<String> appliedChangeIds;
  final String pageDigest;

  Map<String, Object?> toJson() => {
    'stream': stream,
    'cursor': cursor,
    'appliedChangeIds': appliedChangeIds,
    'pageDigest': pageDigest,
  };
}

abstract interface class PosSyncTransport {
  Future<SyncBootstrap> bootstrap(String correlationId);
  Future<SyncPushResponse> push(SyncPushBatch batch);
  Future<SyncEventAck> result(String eventId, String correlationId);
  Future<SyncPullPage> pull({
    required String stream,
    required String correlationId,
    String? cursor,
    int limit = 100,
  });
  Future<void> acknowledge(SyncAckCommand command, String correlationId);
}

final class SyncRunSummary {
  const SyncRunSummary({
    required this.claimed,
    required this.acked,
    required this.retrying,
    required this.deadLetters,
    required this.pulled,
    required this.applied,
    required this.backlog,
    this.errorCode,
  });

  final int claimed;
  final int acked;
  final int retrying;
  final int deadLetters;
  final int pulled;
  final int applied;
  final int backlog;
  final String? errorCode;
}

String syncPageDigest(Iterable<SyncChange> changes) {
  final canonical = StringBuffer();
  for (final change in changes) {
    canonical
      ..write(change.changeId)
      ..write(':')
      ..write(change.payloadHash)
      ..write('\n');
  }
  return canonical.toString();
}

Map<String, Object?> decodeObject(String source) =>
    jsonDecode(source)! as Map<String, Object?>;
