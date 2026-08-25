import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';

import '../../checkout/domain/checkout_models.dart';
import '../../checkout/domain/ulid_generator.dart';
import '../../../infrastructure/local_database/pos_local_database.dart';
import '../application/pos_exchange_application_service.dart';
import '../domain/pos_exchange_models.dart';

/// EXG-001 正式 HTTP + SQLite 命令日志适配器；不包含任何支付机构或设备调用。
final class HttpPosExchangeApplicationService
    implements PosExchangeApplicationService {
  HttpPosExchangeApplicationService({
    required this.baseUri,
    required this.clientId,
    required this.binding,
    required this.database,
    required this.accessTokenProvider,
    UlidGenerator? ulids,
    HttpClient? client,
    this.timeout = const Duration(seconds: 12),
  }) : ulids = ulids ?? UlidGenerator(),
       _client = client ?? HttpClient();

  final Uri baseUri;
  final String clientId;
  final TrustedDeviceBinding binding;
  final PosLocalDatabase database;
  final Future<String> Function() accessTokenProvider;
  final UlidGenerator ulids;
  final HttpClient _client;
  final Duration timeout;

  @override
  Future<PosExchangeView> create({
    required PosExchangeSource source,
    required String reasonCode,
  }) async {
    _validateSource(source, reasonCode);
    final prior = database.database.select(
      '''SELECT exchange_id,request_sha256,return_id,new_order_id
         FROM local_exchange_command
         WHERE tenant_id=? AND (return_id=? OR new_order_id=?)''',
      [
        binding.tenantId,
        source.originalReturn.returnRef,
        source.newSale.orderRef,
      ],
    );
    if (prior.isNotEmpty) {
      if (prior.length != 1 ||
          prior.single['return_id'] != source.originalReturn.returnRef ||
          prior.single['new_order_id'] != source.newSale.orderRef) {
        throw const PosExchangeFailure(
          'EXCHANGE_OWNER_ALREADY_LINKED',
          '原退货或新销售已绑定其他换货，不能重复关联。',
        );
      }
      return refreshExchange(prior.single['exchange_id']! as String);
    }
    final order = database.database.select(
      '''SELECT business_date,status FROM local_order
         WHERE tenant_id=? AND order_id=? AND store_id=? AND terminal_id=?''',
      [
        binding.tenantId,
        source.newSale.orderRef,
        binding.storeId,
        binding.terminalId,
      ],
    );
    if (order.length != 1 || order.single['status'] != 'COMPLETED') {
      throw const PosExchangeFailure(
        'EXCHANGE_NEW_SALE_NOT_AUTHORITATIVE',
        '新销售尚未形成当前终端的权威完成事实。',
      );
    }
    final exchangeId = ulids.next();
    final commandId = ulids.next();
    final correlationId = ulids.next();
    final at = DateTime.now().toUtc();
    final plan = SplayTreeMap<String, Object?>.from({
      'newOrderId': source.newSale.orderRef,
      'newSaleCommandId': source.newSale.commandRef,
      'receivableAmountMinor': source.newSale.receivableAmountMinor,
      'quoteFingerprint': source.newSale.quoteFingerprint,
      'settlementFingerprint': source.newSale.settlementFingerprint,
      'orderSnapshotSha256': source.newSale.snapshotDigest,
    });
    final planHash = sha256.convert(utf8.encode(jsonEncode(plan))).toString();
    final body = <String, Object?>{
      'commandId': commandId,
      'idempotencyKey':
          'exg:${binding.terminalId}:${source.originalReturn.returnRef}:${source.newSale.orderRef}',
      'exchangeId': exchangeId,
      'returnId': source.originalReturn.returnRef,
      'originalOrderId': source.originalReturn.orderRef,
      'originalReturnCommandId': source.originalReturn.requestCommandRef,
      'newOrderId': source.newSale.orderRef,
      'newSaleCommandId': source.newSale.commandRef,
      'storeId': int.parse(binding.storeId),
      'terminalId': binding.terminalId,
      'businessDate': order.single['business_date']! as String,
      'expectedRefundAmountMinor': source.originalReturn.refundableAmountMinor,
      'expectedSaleReceivableMinor': source.newSale.receivableAmountMinor,
      'quoteFingerprint': source.newSale.quoteFingerprint,
      'newSalePlanSha256': planHash,
      'reasonCode': reasonCode,
      'correlationId': correlationId,
      'occurredAt': at.toIso8601String(),
    };
    final requestHash = _digest(body);
    database.transaction(() {
      database.database.execute(
        '''INSERT INTO local_exchange_command(
           exchange_id,tenant_id,command_id,idempotency_key,request_sha256,return_id,
           original_order_id,original_return_command_id,new_order_id,new_sale_command_id,
           store_id,terminal_id,business_date,expected_refund_amount_minor,
           expected_sale_receivable_minor,quote_fingerprint,new_sale_plan_sha256,
           reason_code,correlation_id,server_status,created_at,updated_at)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PREPARED',?,?)''',
        [
          exchangeId,
          binding.tenantId,
          commandId,
          body['idempotencyKey'],
          requestHash,
          source.originalReturn.returnRef,
          source.originalReturn.orderRef,
          source.originalReturn.requestCommandRef,
          source.newSale.orderRef,
          source.newSale.commandRef,
          binding.storeId,
          binding.terminalId,
          body['businessDate'],
          source.originalReturn.refundableAmountMinor,
          source.newSale.receivableAmountMinor,
          source.newSale.quoteFingerprint,
          planHash,
          reasonCode,
          correlationId,
          at.toIso8601String(),
          at.toIso8601String(),
        ],
      );
      _appendLocalEvent(exchangeId, 'PREPARED', 'PREPARED', requestHash, at);
      database.checkpoint('exchange.prepared');
    });
    return _submit(exchangeId, body, requestHash);
  }

  @override
  Future<PosExchangeView> refreshExchange(String exchangeRef) async {
    _requireUlid(exchangeRef);
    final rows = database.database.select(
      '''SELECT * FROM local_exchange_command
         WHERE tenant_id=? AND exchange_id=?''',
      [binding.tenantId, exchangeRef],
    );
    if (rows.length != 1) {
      throw const PosExchangeFailure('EXCHANGE_NOT_FOUND', '本机没有该换货原命令。');
    }
    try {
      final data = await _request('POST', 'pos/exchanges/$exchangeRef/observe');
      return _saveObservation(exchangeRef, data);
    } on _ExchangeHttpFailure catch (error) {
      if (error.resultUnknown) {
        _markUnknown(exchangeRef, rows.single['request_sha256']! as String);
        throw PosExchangeFailure(
          'EXCHANGE_RESULT_UNKNOWN',
          '换货观察结果未知，请继续查询原换货，不要创建新命令。',
          resultUnknown: true,
          exchangeRef: exchangeRef,
        );
      }
      throw PosExchangeFailure(error.code, error.safeMessage);
    }
  }

  @override
  Future<PosExchangeView> approve({
    required String exchangeRef,
    required String correlationRef,
    required String reasonCode,
  }) => _mutateCheckpoint(
    exchangeRef: exchangeRef,
    path: 'pos/exchanges/$exchangeRef/approve',
    body: {
      'commandId': ulids.next(),
      'reasonCode': reasonCode,
      'correlationId': correlationRef,
      'occurredAt': DateTime.now().toUtc().toIso8601String(),
    },
  );

  @override
  Future<PosExchangeView> recover({
    required String exchangeRef,
    required String correlationRef,
    required String targetLeg,
    required String reasonCode,
  }) => _mutateCheckpoint(
    exchangeRef: exchangeRef,
    path: 'pos/exchanges/$exchangeRef/recover',
    body: {
      'commandId': ulids.next(),
      'targetLeg': targetLeg,
      'reasonCode': reasonCode,
      'correlationId': correlationRef,
      'occurredAt': DateTime.now().toUtc().toIso8601String(),
    },
  );

  Future<PosExchangeView> _mutateCheckpoint({
    required String exchangeRef,
    required String path,
    required Map<String, Object?> body,
  }) async {
    _requireUlid(exchangeRef);
    final local = database.database.select(
      '''SELECT request_sha256 FROM local_exchange_command
         WHERE tenant_id=? AND exchange_id=?''',
      [binding.tenantId, exchangeRef],
    );
    if (local.length != 1) {
      throw const PosExchangeFailure('EXCHANGE_NOT_FOUND', '本机没有该换货原命令。');
    }
    try {
      return _saveObservation(
        exchangeRef,
        await _request('POST', path, body: body),
      );
    } on _ExchangeHttpFailure catch (error) {
      if (error.resultUnknown) {
        _markUnknown(exchangeRef, local.single['request_sha256']! as String);
        throw PosExchangeFailure(
          'EXCHANGE_RESULT_UNKNOWN',
          '换货操作结果未知，请查询原换货检查点，禁止创建替代命令。',
          resultUnknown: true,
          exchangeRef: exchangeRef,
        );
      }
      throw PosExchangeFailure(error.code, error.safeMessage);
    }
  }

  Future<PosExchangeView> _submit(
    String exchangeId,
    Map<String, Object?> body,
    String requestHash,
  ) async {
    final at = DateTime.now().toUtc();
    database.transaction(() {
      database.database.execute(
        '''UPDATE local_exchange_command SET server_status='SUBMITTING',updated_at=?
           WHERE tenant_id=? AND exchange_id=? AND server_status='PREPARED' ''',
        [at.toIso8601String(), binding.tenantId, exchangeId],
      );
      if (database.database.updatedRows != 1) {
        throw StateError('EXCHANGE_LOCAL_STATE_CONFLICT');
      }
      _appendLocalEvent(
        exchangeId,
        'SUBMITTING',
        'SUBMITTING',
        requestHash,
        at,
      );
      database.checkpoint('exchange.before-http');
    });
    try {
      final data = await _request('POST', 'pos/exchanges', body: body);
      return _saveObservation(exchangeId, data);
    } on _ExchangeHttpFailure catch (error) {
      if (error.resultUnknown) {
        _markUnknown(exchangeId, requestHash);
        throw PosExchangeFailure(
          'EXCHANGE_RESULT_UNKNOWN',
          '换货提交结果未知，请按原换货标识查询，禁止重新创建。',
          resultUnknown: true,
          exchangeRef: exchangeId,
        );
      }
      throw PosExchangeFailure(error.code, error.safeMessage);
    }
  }

  PosExchangeView _saveObservation(
    String exchangeId,
    Map<String, Object?> data,
  ) {
    final view = _view(data);
    if (view.exchangeRef != exchangeId) {
      throw const PosExchangeFailure(
        'EXCHANGE_RESPONSE_INVALID',
        '服务端换货身份与原命令不一致。',
      );
    }
    final digest = _digest(data);
    database.transaction(() {
      database.database.execute(
        '''UPDATE local_exchange_command SET server_status=?,server_record_version=?,
           server_updated_at=?,updated_at=? WHERE tenant_id=? AND exchange_id=?''',
        [
          view.status.wire,
          view.recordVersion,
          view.updatedAt.toIso8601String(),
          DateTime.now().toUtc().toIso8601String(),
          binding.tenantId,
          exchangeId,
        ],
      );
      if (database.database.updatedRows != 1) {
        throw StateError('EXCHANGE_LOCAL_STATE_CONFLICT');
      }
      _appendLocalEvent(
        exchangeId,
        'OBSERVED',
        view.status.wire,
        digest,
        DateTime.now().toUtc(),
      );
      database.checkpoint('exchange.observed');
    });
    return view;
  }

  void _markUnknown(String exchangeId, String payloadHash) {
    final at = DateTime.now().toUtc();
    database.transaction(() {
      database.database.execute(
        '''UPDATE local_exchange_command SET server_status='UNKNOWN',updated_at=?
           WHERE tenant_id=? AND exchange_id=?''',
        [at.toIso8601String(), binding.tenantId, exchangeId],
      );
      _appendLocalEvent(exchangeId, 'UNKNOWN', 'UNKNOWN', payloadHash, at);
    });
  }

  void _appendLocalEvent(
    String exchangeId,
    String type,
    String status,
    String payloadHash,
    DateTime at,
  ) {
    database.database.execute(
      '''INSERT INTO local_exchange_event(event_id,tenant_id,exchange_id,event_type,status,
         payload_sha256,occurred_at) VALUES(?,?,?,?,?,?,?)''',
      [
        ulids.next(),
        binding.tenantId,
        exchangeId,
        type,
        status,
        payloadHash,
        at.toIso8601String(),
      ],
    );
  }

  Future<Map<String, Object?>> _request(
    String method,
    String path, {
    Map<String, Object?>? body,
  }) async {
    try {
      final request = await _client
          .openUrl(method, baseUri.resolve(path))
          .timeout(timeout);
      request.headers
        ..set(
          HttpHeaders.authorizationHeader,
          'Bearer ${await accessTokenProvider()}',
        )
        ..set('clientid', clientId)
        ..set('X-Device-Id', binding.terminalId)
        ..set(HttpHeaders.acceptHeader, ContentType.json.mimeType);
      if (body != null) {
        request.headers.contentType = ContentType.json;
        request.write(jsonEncode(body));
      }
      final response = await request.close().timeout(timeout);
      final text = await utf8.decoder.bind(response).join().timeout(timeout);
      final envelope = (jsonDecode(text) as Map).cast<String, Object?>();
      final code = envelope['code'];
      if (response.statusCode < 200 ||
          response.statusCode >= 300 ||
          (code is num && code.toInt() != 200)) {
        throw _ExchangeHttpFailure(
          'EXCHANGE_HTTP_${response.statusCode}',
          _safeMessage(envelope['msg']),
          resultUnknown:
              response.statusCode == 408 ||
              response.statusCode == 429 ||
              response.statusCode >= 500,
        );
      }
      final data = envelope['data'];
      if (data is! Map) {
        throw const _ExchangeHttpFailure(
          'EXCHANGE_RESPONSE_INVALID',
          '服务端缺少换货事实。',
        );
      }
      return data.cast<String, Object?>();
    } on _ExchangeHttpFailure {
      rethrow;
    } on TimeoutException {
      throw const _ExchangeHttpFailure(
        'EXCHANGE_NETWORK_TIMEOUT',
        '网络超时。',
        resultUnknown: true,
      );
    } on SocketException {
      throw const _ExchangeHttpFailure(
        'EXCHANGE_NETWORK_UNAVAILABLE',
        '网络不可用。',
        resultUnknown: true,
      );
    } on FormatException {
      throw const _ExchangeHttpFailure(
        'EXCHANGE_RESPONSE_INVALID',
        '服务端响应格式无效。',
      );
    }
  }

  PosExchangeView _view(Map<String, Object?> data) => PosExchangeView(
    exchangeRef: _text(data, 'exchangeId'),
    returnRef: _text(data, 'returnId'),
    newOrderRef: _text(data, 'newOrderId'),
    status: PosExchangeStatus.fromWire(_text(data, 'status')),
    expectedRefundAmountMinor: _integer(data, 'expectedRefundAmountMinor'),
    expectedSaleReceivableMinor: _integer(data, 'expectedSaleReceivableMinor'),
    displayDifferenceMinor: _integer(data, 'displayDifferenceMinor'),
    correlationRef: _text(data, 'correlationId'),
    recordVersion: _integer(data, 'recordVersion'),
    updatedAt: DateTime.parse(_text(data, 'updatedAt')).toUtc(),
    duplicate: data['duplicate'] == true,
  );

  void _validateSource(PosExchangeSource source, String reasonCode) {
    if (source.originalReturn.status.name != 'completed' ||
        source.originalReturn.refundableAmountMinor <= 0 ||
        source.newSale.receivableAmountMinor <= 0 ||
        !RegExp(r'^[A-Z0-9_]{2,32}$').hasMatch(reasonCode)) {
      throw const PosExchangeFailure(
        'EXCHANGE_SOURCE_INVALID',
        '只有已完成的原退货和新现金销售可以建立换货关联。',
      );
    }
  }

  String _digest(Map<String, Object?> value) => sha256
      .convert(
        utf8.encode(jsonEncode(SplayTreeMap<String, Object?>.from(value))),
      )
      .toString();

  void _requireUlid(String value) {
    if (!RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(value)) {
      throw const PosExchangeFailure('EXCHANGE_ID_INVALID', '换货标识无效。');
    }
  }

  void close() => _client.close(force: true);
}

final class _ExchangeHttpFailure implements Exception {
  const _ExchangeHttpFailure(
    this.code,
    this.safeMessage, {
    this.resultUnknown = false,
  });
  final String code;
  final String safeMessage;
  final bool resultUnknown;
}

String _safeMessage(Object? value) {
  final text = value is String ? value.trim() : '';
  if (text.isEmpty || text.length > 180) return '换货请求被服务端拒绝。';
  return text.replaceAll(RegExp(r'[\r\n\t]+'), ' ');
}

String _text(Map<String, Object?> source, String key) {
  final value = source[key];
  if (value is String && value.isNotEmpty) return value;
  if (value is num) return value.toString();
  throw const PosExchangeFailure('EXCHANGE_RESPONSE_INVALID', '换货字段缺失。');
}

int _integer(Map<String, Object?> source, String key) {
  final value = source[key];
  if (value is int) return value;
  if (value is num) return value.toInt();
  final parsed = int.tryParse(value.toString());
  if (parsed != null) return parsed;
  throw const PosExchangeFailure('EXCHANGE_RESPONSE_INVALID', '换货金额或版本缺失。');
}
