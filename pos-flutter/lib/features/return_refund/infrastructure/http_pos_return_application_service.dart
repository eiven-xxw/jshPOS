import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../../checkout/domain/ulid_generator.dart';
import '../../checkout/domain/checkout_models.dart';
import '../application/pos_return_application_service.dart';
import '../domain/pos_return_models.dart';

/// POS 原单退货退款正式服务端适配器；仅调用自有 Return Owner API，不包含支付机构网络。
final class HttpPosReturnApplicationService
    implements PosReturnApplicationService {
  HttpPosReturnApplicationService({
    required this.baseUri,
    required this.clientId,
    required this.binding,
    required this.accessTokenProvider,
    required this.currentShiftIdProvider,
    required this.returnWarehouseIdProvider,
    UlidGenerator? ulids,
    HttpClient? client,
    this.timeout = const Duration(seconds: 12),
  }) : ulids = ulids ?? UlidGenerator(),
       _client = client ?? HttpClient();

  final Uri baseUri;
  final String clientId;
  final TrustedDeviceBinding binding;
  final Future<String> Function() accessTokenProvider;
  final String Function() currentShiftIdProvider;
  final String Function() returnWarehouseIdProvider;
  final UlidGenerator ulids;
  final HttpClient _client;
  final Duration timeout;

  String? _orderQuery;
  PosReturnWorkspace? _workspace;
  final Map<String, String> _requested = {};
  _PendingReturn? _pending;

  @override
  Future<PosReturnWorkspace> findOriginalOrder(String orderQuery) async {
    _orderQuery = orderQuery.trim();
    _requested.clear();
    _pending = null;
    return _preview();
  }

  @override
  Future<PosReturnWorkspace> changeRequestedQuantity(
    String orderLineRef,
    String quantity,
  ) async {
    if (_orderQuery == null || _workspace == null) {
      throw const PosReturnFailure('RETURN_ORDER_REQUIRED', '请先查询原单。');
    }
    final normalized = _quantity(quantity);
    if (normalized == '0') {
      _requested.remove(orderLineRef);
    } else {
      _requested[orderLineRef] = normalized;
    }
    _pending = null;
    return _preview();
  }

  @override
  Future<PosReturnSubmissionView> submitCashReturn({
    required String reasonCode,
    String? supervisorCredential,
  }) async {
    final workspace = _workspace;
    if (workspace == null || !workspace.canSubmit) {
      throw const PosReturnFailure('RETURN_SELECTION_REQUIRED', '请选择合法退货数量。');
    }
    if (supervisorCredential != null && supervisorCredential.isNotEmpty) {
      throw const PosReturnFailure(
        'RETURN_SUPERVISOR_INLINE_UNSUPPORTED',
        '审批必须由独立受权员工完成，审批凭据不会随退货请求传输。',
      );
    }
    if (workspace.settlementKind != 'CASH') {
      throw const PosReturnFailure(
        'RETURN_PROVIDER_BLOCKED',
        '当前仅开放现金原单退货；第三方支付仍处于阻断状态。',
      );
    }
    final pending = _pending ??= _PendingReturn(
      commandId: ulids.next(),
      returnId: ulids.next(),
      correlationId: ulids.next(),
    );
    final body = <String, Object?>{
      'commandId': pending.commandId,
      'idempotencyKey':
          'pos-return:${binding.terminalId}:${workspace.orderRef}:${pending.returnId}',
      'returnId': pending.returnId,
      'orderId': workspace.orderRef,
      'storeId': binding.storeId,
      'terminalId': binding.terminalId,
      'refundShiftId': currentShiftIdProvider(),
      'warehouseId': returnWarehouseIdProvider(),
      'businessDate': workspace.businessDate,
      'settlementKind': 'CASH',
      'paymentId': null,
      'reasonCode': reasonCode,
      'lines': _requested.entries
          .map(
            (entry) => <String, Object?>{
              'orderLineId': entry.key,
              'quantity': entry.value,
            },
          )
          .toList(growable: false),
      'correlationId': pending.correlationId,
      'occurredAt': DateTime.now().toUtc().toIso8601String(),
    };
    try {
      final data = await _request('POST', 'returns', body: body);
      return _submission(data, fallbackAmount: workspace.refundableAmountMinor);
    } on _ReturnHttpFailure catch (error) {
      if (error.resultUnknown) {
        throw PosReturnFailure(
          'RETURN_RESULT_UNKNOWN',
          '退货提交结果未知，请按原申请继续查询。',
          resultUnknown: true,
          returnRef: pending.returnId,
        );
      }
      throw PosReturnFailure(error.code, error.safeMessage);
    }
  }

  @override
  Future<PosReturnSubmissionView> refreshReturnStatus(String returnRef) async {
    try {
      final data = await _request('GET', 'returns/$returnRef');
      return _submission(
        data,
        fallbackAmount: _workspace?.refundableAmountMinor ?? 0,
      );
    } on _ReturnHttpFailure catch (error) {
      throw PosReturnFailure(error.code, error.safeMessage);
    }
  }

  Future<PosReturnWorkspace> _preview() async {
    final query = _orderQuery;
    if (query == null) {
      throw const PosReturnFailure('RETURN_ORDER_REQUIRED', '请先查询原单。');
    }
    try {
      final data = await _request(
        'POST',
        'returns/preview',
        body: <String, Object?>{
          'orderQuery': query,
          'lines': _requested.entries
              .map(
                (entry) => <String, Object?>{
                  'orderLineId': entry.key,
                  'quantity': entry.value,
                },
              )
              .toList(growable: false),
        },
      );
      return _workspace = _workspaceFrom(data);
    } on _ReturnHttpFailure catch (error) {
      throw PosReturnFailure(error.code, error.safeMessage);
    }
  }

  Future<Map<String, Object?>> _request(
    String method,
    String path, {
    Map<String, Object?>? body,
  }) async {
    final target = baseUri.resolve(path);
    try {
      final request = await _client.openUrl(method, target).timeout(timeout);
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
      Map<String, Object?> envelope;
      try {
        envelope = jsonDecode(text) as Map<String, Object?>;
      } catch (_) {
        throw const _ReturnHttpFailure('RETURN_RESPONSE_INVALID', '服务端响应格式无效。');
      }
      final businessCode = envelope['code'];
      if (response.statusCode < 200 ||
          response.statusCode >= 300 ||
          (businessCode is num && businessCode.toInt() != 200)) {
        throw _ReturnHttpFailure(
          'RETURN_HTTP_${response.statusCode}',
          _safeRemoteMessage(envelope['msg']),
          resultUnknown:
              response.statusCode == 408 ||
              response.statusCode == 429 ||
              response.statusCode >= 500,
        );
      }
      final data = envelope['data'];
      if (data is! Map) {
        throw const _ReturnHttpFailure('RETURN_RESPONSE_INVALID', '服务端缺少退货事实。');
      }
      return data.cast<String, Object?>();
    } on _ReturnHttpFailure {
      rethrow;
    } on TimeoutException {
      throw const _ReturnHttpFailure(
        'RETURN_NETWORK_TIMEOUT',
        '网络超时，请稍后重试或查询原申请。',
        resultUnknown: true,
      );
    } on SocketException {
      throw const _ReturnHttpFailure(
        'RETURN_NETWORK_UNAVAILABLE',
        '网络不可用，请稍后重试或查询原申请。',
        resultUnknown: true,
      );
    }
  }

  PosReturnWorkspace _workspaceFrom(Map<String, Object?> data) {
    final storeId = _text(data, 'storeId');
    return PosReturnWorkspace(
      orderRef: _text(data, 'orderId'),
      localOrderNo: _text(data, 'localOrderNo'),
      storeName: '门店 $storeId',
      businessDate: _text(data, 'businessDate'),
      currency: _text(data, 'currency'),
      settlementKind: _text(data, 'settlementKind'),
      promotionSnapshotRef: _text(data, 'promotionSnapshotId'),
      promotionSnapshotSha256: _text(data, 'promotionSnapshotSha256'),
      originalReceivableAmountMinor: _integer(
        data,
        'originalReceivableAmountMinor',
      ),
      cumulativeRefundedAmountMinor: _integer(
        data,
        'cumulativeRefundedAmountMinor',
      ),
      maximumRefundableAmountMinor: _integer(
        data,
        'maximumRefundableAmountMinor',
      ),
      requestedGrossAmountMinor: _integer(data, 'requestedGrossAmountMinor'),
      recoveredDiscountAmountMinor: _integer(
        data,
        'recoveredDiscountAmountMinor',
      ),
      refundableAmountMinor: _integer(data, 'refundableAmountMinor'),
      lines: _list(data, 'lines').map(
        (line) => PosReturnLineView(
          lineRef: _text(line, 'orderLineId'),
          skuCode: _text(line, 'skuCode'),
          name: _text(line, 'productName'),
          unitName: _text(line, 'unitCode'),
          originalQuantity: _decimal(line, 'originalQuantity'),
          cumulativeReturnedQuantity: _decimal(
            line,
            'cumulativeReturnedQuantity',
          ),
          maximumReturnableQuantity: _decimal(
            line,
            'maximumReturnableQuantity',
          ),
          requestedQuantity: _decimal(line, 'requestedQuantity'),
          requestedGrossMinor: _integer(line, 'requestedGrossMinor'),
          recoveredDiscountMinor: _integer(line, 'recoveredDiscountMinor'),
          refundableAmountMinor: _integer(line, 'refundableAmountMinor'),
        ),
      ),
    );
  }

  PosReturnSubmissionView _submission(
    Map<String, Object?> data, {
    required int fallbackAmount,
  }) {
    final returnRef = _text(data, 'returnId');
    final version = _integer(data, 'recordVersion');
    return PosReturnSubmissionView(
      returnRef: returnRef,
      requestCommandRef: _text(data, 'requestCommandId'),
      orderRef: _text(data, 'orderId'),
      status: PosReturnSagaStatus.fromWire(_text(data, 'status')),
      refundableAmountMinor:
          _nullableInteger(data, 'refundableAmountMinor') ?? fallbackAmount,
      promotionSnapshotRef: _text(data, 'promotionSnapshotId'),
      promotionSnapshotSha256: _text(data, 'promotionSnapshotSha256'),
      auditRef: 'RETURN_HISTORY:$returnRef:$version',
      correlationRef: _text(data, 'correlationId'),
      updatedAt: DateTime.parse(_text(data, 'updatedAt')).toUtc(),
      duplicate: data['duplicate'] == true,
    );
  }

  void close() => _client.close(force: true);
}

final class _PendingReturn {
  const _PendingReturn({
    required this.commandId,
    required this.returnId,
    required this.correlationId,
  });

  final String commandId;
  final String returnId;
  final String correlationId;
}

final class _ReturnHttpFailure implements Exception {
  const _ReturnHttpFailure(
    this.code,
    this.safeMessage, {
    this.resultUnknown = false,
  });

  final String code;
  final String safeMessage;
  final bool resultUnknown;
}

String _safeRemoteMessage(Object? value) {
  final text = value is String ? value.trim() : '';
  if (text.isEmpty || text.length > 180) return '退货请求被服务端拒绝。';
  return text.replaceAll(RegExp(r'[\r\n\t]+'), ' ');
}

String _quantity(String value) {
  final match = RegExp(r'^(0|[1-9][0-9]{0,12})(?:\.([0-9]{1,6}))?$')
      .firstMatch(value.trim());
  if (match == null) {
    throw const PosReturnFailure('RETURN_QUANTITY_INVALID', '退货数量格式无效。');
  }
  final fraction = (match.group(2) ?? '').replaceFirst(RegExp(r'0+$'), '');
  return fraction.isEmpty ? match.group(1)! : '${match.group(1)}.$fraction';
}

String _text(Map<String, Object?> source, String key) {
  final value = source[key];
  if (value is String && value.isNotEmpty) return value;
  if (value is num) return value.toString();
  throw const PosReturnFailure('RETURN_RESPONSE_INVALID', '服务端退货字段缺失。');
}

String _decimal(Map<String, Object?> source, String key) {
  final value = source[key];
  if (value is String) return value;
  if (value is num) return value.toString();
  throw const PosReturnFailure('RETURN_RESPONSE_INVALID', '服务端数量字段缺失。');
}

int _integer(Map<String, Object?> source, String key) {
  final value = _nullableInteger(source, key);
  if (value != null) return value;
  throw const PosReturnFailure('RETURN_RESPONSE_INVALID', '服务端金额字段缺失。');
}

int? _nullableInteger(Map<String, Object?> source, String key) {
  final value = source[key];
  if (value == null) return null;
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value.toString());
}

List<Map<String, Object?>> _list(Map<String, Object?> source, String key) {
  final value = source[key];
  if (value is! List) {
    throw const PosReturnFailure('RETURN_RESPONSE_INVALID', '服务端退货行缺失。');
  }
  return value
      .map((item) => (item as Map).cast<String, Object?>())
      .toList(growable: false);
}
