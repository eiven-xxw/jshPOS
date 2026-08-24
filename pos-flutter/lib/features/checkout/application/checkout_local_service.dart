import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:sqlite3/sqlite3.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../shift/domain/shift_models.dart';
import '../domain/checkout_models.dart';
import '../domain/exact_quantity.dart';
import '../domain/promoted_order_snapshot_codec.dart';
import '../domain/ulid_generator.dart';
import 'lot_checkout_allocator.dart';

part 'checkout_local_persistence.dart';
part 'checkout_local_receipt_operations.dart';
part 'checkout_local_settlement_operations.dart';
part 'checkout_local_shift_operations.dart';

final class PosDomainException implements Exception {
  const PosDomainException(this.code, this.message);
  final String code;
  final String message;
  @override
  String toString() => '$code: $message';
}

final class CheckoutLocalService with _CheckoutLocalPersistenceMixin {
  CheckoutLocalService({
    required this.localDatabase,
    required this.ulids,
    required this.shiftPolicy,
  });

  @override
  final PosLocalDatabase localDatabase;
  @override
  final UlidGenerator ulids;
  @override
  final ShiftPolicy shiftPolicy;
  @override
  Database get _db => localDatabase.database;
  @override
  TrustedDeviceBinding get _binding => localDatabase.binding;

  void suspendBasket({
    required String commandId,
    required String idempotencyKey,
    required Basket basket,
    required String shiftId,
    required DateTime occurredAt,
  }) {
    _requireCommand(commandId, idempotencyKey);
    if (basket.lines.isEmpty) {
      throw const PosDomainException('ORDER_STATE_CONFLICT', 'basket is empty');
    }
    final requestHash = _hash([
      'SUSPEND',
      basket.orderId,
      basket.localOrderNo,
      shiftId,
      _binding.tenantId,
      _binding.storeId,
      _binding.terminalId,
      _binding.cashierId,
    ]);
    localDatabase.transaction(() {
      final duplicate = _idempotent<Map<String, Object?>>(
        'SUSPEND_BASKET',
        idempotencyKey,
        requestHash,
        (json, {bool duplicate = false}) => json,
      );
      if (duplicate != null) return;
      if (basket.suspended) {
        throw const PosDomainException(
          'ORDER_STATE_CONFLICT',
          'basket is already suspended under another command',
        );
      }
      _requireOpenShift(shiftId);
      final existing = _db.select(
        'SELECT * FROM local_order WHERE tenant_id=? AND order_id=?',
        [_binding.tenantId, basket.orderId],
      );
      int priorVersion;
      if (existing.isEmpty) {
        _insertDraftOrder(basket, shiftId, occurredAt);
        priorVersion = 1;
      } else if (!_matchesDraftContext(
            existing.single,
            shiftId,
            basket.localOrderNo,
          ) ||
          existing.single['draft_disposition'] != 'ACTIVE') {
        throw const PosDomainException(
          'ORDER_STATE_CONFLICT',
          'only an active draft in the same trusted shift can be suspended',
        );
      } else {
        priorVersion = existing.single['record_version']! as int;
      }
      _db.execute(
        'UPDATE local_order SET draft_disposition=\'SUSPENDED\',record_version=record_version+1 WHERE tenant_id=? AND order_id=? AND status=\'DRAFT\' AND draft_disposition=\'ACTIVE\' AND record_version=?',
        [_binding.tenantId, basket.orderId, priorVersion],
      );
      if (_db.updatedRows != 1) {
        throw const PosDomainException(
          'ORDER_VERSION_CONFLICT',
          'suspend conflict',
        );
      }
      localDatabase.checkpoint('basket.suspended');
      final at = occurredAt.toUtc().toIso8601String();
      final version = priorVersion + 1;
      _appendOutbox(
        stream: 'order.command',
        eventType: 'order.suspended.v1',
        aggregateId: basket.orderId,
        aggregateVersion: version,
        correlationId: commandId,
        payload: {
          'orderId': basket.orderId,
          'storeId': _binding.storeId,
          'terminalId': _binding.terminalId,
          'shiftId': shiftId,
          'aggregateVersion': version,
        },
        occurredAt: at,
      );
      _audit(
        'ORDER_SUSPENDED',
        'ORDER',
        basket.orderId,
        commandId,
        'DRAFT_ACTIVE',
        'DRAFT_SUSPENDED',
        null,
        requestHash,
        at,
      );
      _saveIdempotency(
        'SUSPEND_BASKET',
        commandId,
        idempotencyKey,
        requestHash,
        basket.orderId,
        {'orderId': basket.orderId, 'aggregateVersion': version},
        at,
      );
    });
    basket.suspend();
  }

  Basket resumeBasket({
    required String commandId,
    required String idempotencyKey,
    required String orderId,
    required String shiftId,
    required DateTime occurredAt,
  }) {
    _requireCommand(commandId, idempotencyKey);
    if (!UlidGenerator.isCanonical(orderId) ||
        !UlidGenerator.isCanonical(shiftId)) {
      throw const PosDomainException(
        'ORDER_INPUT_INVALID',
        'order or shift identity is invalid',
      );
    }
    final requestHash = _hash([
      'RESUME',
      orderId,
      shiftId,
      _binding.tenantId,
      _binding.storeId,
      _binding.terminalId,
      _binding.cashierId,
    ]);
    return localDatabase.transaction(() {
      final duplicate = _idempotent<Map<String, Object?>>(
        'RESUME_BASKET',
        idempotencyKey,
        requestHash,
        (json, {bool duplicate = false}) => json,
      );
      _requireOpenShift(shiftId);
      final orders = _db.select(
        'SELECT * FROM local_order WHERE tenant_id=? AND store_id=? AND terminal_id=? AND cashier_id=? AND shift_id=? AND order_id=?',
        [
          _binding.tenantId,
          _binding.storeId,
          _binding.terminalId,
          _binding.cashierId,
          shiftId,
          orderId,
        ],
      );
      if (orders.length != 1 ||
          orders.single['status'] != 'DRAFT' ||
          (duplicate == null
              ? orders.single['draft_disposition'] != 'SUSPENDED'
              : orders.single['draft_disposition'] != 'ACTIVE')) {
        throw const PosDomainException(
          'RESOURCE_NOT_VISIBLE',
          'suspended order is unavailable',
        );
      }
      final rows = _db.select(
        'SELECT * FROM local_order_line WHERE tenant_id=? AND order_id=? ORDER BY line_no',
        [_binding.tenantId, orderId],
      );
      final lines = rows.map(
        (row) => BasketLine(
          lineId: row['line_id']! as String,
          lineNo: row['line_no']! as int,
          quote: PriceQuote.fromVerifiedPackage(
            skuId: row['sku_id']! as String,
            skuCode: row['sku_code']! as String,
            productName: row['product_name_snapshot']! as String,
            unitId: row['unit_id']! as String,
            unitCode: row['unit_code']! as String,
            unitPriceMinor: row['unit_price_minor']! as int,
            priceSource: row['price_source']! as String,
            barcode: row['barcode_value'] as String?,
          ),
          quantity: row['quantity_decimal']! as String,
        ),
      );
      if (duplicate != null) {
        return Basket(
          orderId: orderId,
          localOrderNo: orders.single['local_order_no']! as String,
          lines: lines,
        );
      }
      final priorVersion = orders.single['record_version']! as int;
      _db.execute(
        'UPDATE local_order SET draft_disposition=\'ACTIVE\',record_version=record_version+1 WHERE tenant_id=? AND order_id=? AND status=\'DRAFT\' AND draft_disposition=\'SUSPENDED\' AND record_version=?',
        [_binding.tenantId, orderId, priorVersion],
      );
      if (_db.updatedRows != 1) {
        throw const PosDomainException(
          'ORDER_VERSION_CONFLICT',
          'resume conflict',
        );
      }
      final at = occurredAt.toUtc().toIso8601String();
      final version = priorVersion + 1;
      _appendOutbox(
        stream: 'order.command',
        eventType: 'order.resumed.v1',
        aggregateId: orderId,
        aggregateVersion: version,
        correlationId: commandId,
        payload: {
          'orderId': orderId,
          'storeId': _binding.storeId,
          'terminalId': _binding.terminalId,
          'shiftId': shiftId,
          'aggregateVersion': version,
        },
        occurredAt: at,
      );
      _audit(
        'ORDER_RESUMED',
        'ORDER',
        orderId,
        commandId,
        'DRAFT_SUSPENDED',
        'DRAFT_ACTIVE',
        null,
        requestHash,
        at,
      );
      _saveIdempotency(
        'RESUME_BASKET',
        commandId,
        idempotencyKey,
        requestHash,
        orderId,
        {'orderId': orderId, 'aggregateVersion': version},
        at,
      );
      return Basket(
        orderId: orderId,
        localOrderNo: orders.single['local_order_no']! as String,
        lines: lines,
      );
    });
  }

  /// 将当前内存购物篮固化为取消事实；不删除订单行，也不产生资金或库存效果。
  OrderDispositionResult cancelBasket({
    required String commandId,
    required String idempotencyKey,
    required Basket basket,
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required DateTime occurredAt,
  }) {
    if (basket.lines.isEmpty) {
      throw const PosDomainException('ORDER_STATE_CONFLICT', 'basket is empty');
    }
    return _cancelBeforeCompletion(
      commandId: commandId,
      idempotencyKey: idempotencyKey,
      orderId: basket.orderId,
      shiftId: shiftId,
      reasonCode: reasonCode,
      reasonText: reasonText,
      occurredAt: occurredAt,
      basket: basket,
    );
  }

  /// 取消已持久化的挂单或未完成订单；只允许可信当前班次内的未支付事实。
  OrderDispositionResult cancelPersistedOrder({
    required String commandId,
    required String idempotencyKey,
    required String orderId,
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required DateTime occurredAt,
  }) => _cancelBeforeCompletion(
    commandId: commandId,
    idempotencyKey: idempotencyKey,
    orderId: orderId,
    shiftId: shiftId,
    reasonCode: reasonCode,
    reasonText: reasonText,
    occurredAt: occurredAt,
  );

  /// 已完成交易只追加受控反向处置路由，订单状态和既有事实保持不变。
  OrderDispositionResult routeCompletedOrder({
    required String commandId,
    required String idempotencyKey,
    required String orderId,
    required String actionShiftId,
    required String routeCode,
    required String reasonCode,
    required String reasonText,
    String? authorizationRef,
    required DateTime occurredAt,
  }) {
    _requireCommand(commandId, idempotencyKey);
    _requireDispositionInput(reasonCode, reasonText);
    const routes = {
      'RETURN_REFUND_REQUIRED',
      'PAYMENT_REVERSAL_OBSERVATION_REQUIRED',
      'EXPLICIT_COMPENSATION_REQUIRED',
    };
    if (!UlidGenerator.isCanonical(orderId) ||
        !routes.contains(routeCode) ||
        (routeCode != 'RETURN_REFUND_REQUIRED' &&
            (authorizationRef == null ||
                authorizationRef.length < 16 ||
                authorizationRef.length > 128))) {
      throw const PosDomainException(
        'ORDER_DISPOSITION_INVALID',
        'completed-order disposition is invalid',
      );
    }
    return localDatabase.transaction(() {
      final shift = _requireOpenShift(actionShiftId);
      final orders = _db.select(
        '''SELECT * FROM local_order WHERE tenant_id=? AND store_id=? AND terminal_id=?
           AND order_id=?''',
        [_binding.tenantId, _binding.storeId, _binding.terminalId, orderId],
      );
      if (orders.length != 1) {
        throw const PosDomainException(
          'RESOURCE_NOT_VISIBLE',
          'completed order is unavailable',
        );
      }
      final order = orders.single;
      final fromStatus = order['status']! as String;
      if (!const {'CONFIRMED', 'COMPLETED'}.contains(fromStatus) ||
          order['payment_status'] != 'PAID') {
        throw const PosDomainException(
          'ORDER_DISPOSITION_REQUIRED',
          'order is not an immutable completed transaction',
        );
      }
      final snapshotHash = _persistedOrderHash(orderId);
      final requestHash = _dispositionHash(
        orderId: orderId,
        shiftId: actionShiftId,
        businessDate: shift['business_date']! as String,
        dispositionType: routeCode,
        fromStatus: fromStatus,
        effectiveStatus: fromStatus,
        reasonCode: reasonCode,
        reasonText: reasonText,
        authorizationRef: authorizationRef,
        snapshotHash: snapshotHash,
      );
      final duplicate = _idempotent<OrderDispositionResult>(
        'ROUTE_ORDER_DISPOSITION',
        idempotencyKey,
        requestHash,
        OrderDispositionResult.fromJson,
      );
      if (duplicate != null) return duplicate;
      final at = occurredAt.toUtc().toIso8601String();
      final dispositionId = ulids.next();
      final version = order['record_version']! as int;
      _insertOrderDisposition(
        dispositionId: dispositionId,
        orderId: orderId,
        shiftId: actionShiftId,
        businessDate: shift['business_date']! as String,
        dispositionType: routeCode,
        fromStatus: fromStatus,
        effectiveStatus: fromStatus,
        reasonCode: reasonCode,
        reasonText: reasonText,
        authorizationRef: authorizationRef,
        snapshotHash: snapshotHash,
        commandId: commandId,
        idempotencyKey: idempotencyKey,
        requestHash: requestHash,
        aggregateVersion: version,
        at: at,
      );
      localDatabase.checkpoint('order.disposition.inserted');
      final eventId = _appendOutbox(
        stream: 'order.command',
        eventType: 'order.reversal-routed.v1',
        aggregateId: dispositionId,
        aggregateVersion: 1,
        correlationId: commandId,
        payload: _dispositionPayload(
          dispositionId: dispositionId,
          orderId: orderId,
          shiftId: actionShiftId,
          businessDate: shift['business_date']! as String,
          dispositionType: routeCode,
          fromStatus: fromStatus,
          effectiveStatus: fromStatus,
          reasonCode: reasonCode,
          reasonText: reasonText,
          authorizationRef: authorizationRef,
          snapshotHash: snapshotHash,
          requestHash: requestHash,
          aggregateVersion: version,
          occurredAt: at,
        ),
        occurredAt: at,
      );
      _audit(
        'ORDER_REVERSAL_ROUTED',
        'ORDER',
        orderId,
        commandId,
        fromStatus,
        fromStatus,
        null,
        requestHash,
        at,
      );
      final result = OrderDispositionResult(
        dispositionId: dispositionId,
        orderId: orderId,
        dispositionType: routeCode,
        fromStatus: fromStatus,
        effectiveStatus: fromStatus,
        requestSha256: requestHash,
        outboxEventId: eventId,
      );
      _saveIdempotency(
        'ROUTE_ORDER_DISPOSITION',
        commandId,
        idempotencyKey,
        requestHash,
        orderId,
        result.toJson(),
        at,
      );
      return result;
    });
  }

  OrderDispositionResult _cancelBeforeCompletion({
    required String commandId,
    required String idempotencyKey,
    required String orderId,
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required DateTime occurredAt,
    Basket? basket,
  }) {
    _requireCommand(commandId, idempotencyKey);
    _requireDispositionInput(reasonCode, reasonText);
    if (!UlidGenerator.isCanonical(orderId) ||
        !UlidGenerator.isCanonical(shiftId)) {
      throw const PosDomainException(
        'ORDER_DISPOSITION_INVALID',
        'order or shift identity is invalid',
      );
    }
    return localDatabase.transaction(() {
      final shift = _requireOpenShift(shiftId);
      var orders = _db.select(
        '''SELECT * FROM local_order WHERE tenant_id=? AND store_id=? AND terminal_id=?
           AND cashier_id=? AND shift_id=? AND order_id=?''',
        [
          _binding.tenantId,
          _binding.storeId,
          _binding.terminalId,
          _binding.cashierId,
          shiftId,
          orderId,
        ],
      );
      if (orders.isEmpty && basket != null) {
        _insertDraftOrder(basket, shiftId, occurredAt);
        orders = _db.select(
          'SELECT * FROM local_order WHERE tenant_id=? AND order_id=?',
          [_binding.tenantId, orderId],
        );
      }
      if (orders.length != 1 ||
          orders.single['business_date'] != shift['business_date']) {
        throw const PosDomainException(
          'RESOURCE_NOT_VISIBLE',
          'unfinished order is outside trusted shift or business date',
        );
      }
      final order = orders.single;
      final fromStatus = order['status']! as String;
      if (fromStatus == 'CANCELLED') {
        final prior = _db.select(
          '''SELECT * FROM local_order_disposition WHERE tenant_id=? AND order_id=?
             AND disposition_type='CANCEL_BEFORE_COMPLETION' AND idempotency_key=?''',
          [_binding.tenantId, orderId, idempotencyKey],
        );
        if (prior.length != 1) {
          throw const PosDomainException(
            'ORDER_STATE_CONFLICT',
            'cancelled order cannot accept a new command',
          );
        }
        final disposition = prior.single;
        final priorHash = _dispositionHash(
          orderId: orderId,
          shiftId: shiftId,
          businessDate: shift['business_date']! as String,
          dispositionType: 'CANCEL_BEFORE_COMPLETION',
          fromStatus: disposition['from_status']! as String,
          effectiveStatus: 'CANCELLED',
          reasonCode: reasonCode,
          reasonText: reasonText,
          snapshotHash: disposition['order_snapshot_sha256']! as String,
        );
        final duplicate = _idempotent<OrderDispositionResult>(
          'CANCEL_ORDER',
          idempotencyKey,
          priorHash,
          OrderDispositionResult.fromJson,
        );
        if (duplicate != null) return duplicate;
      }
      final snapshotHash = _persistedOrderHash(orderId);
      final requestHash = _dispositionHash(
        orderId: orderId,
        shiftId: shiftId,
        businessDate: shift['business_date']! as String,
        dispositionType: 'CANCEL_BEFORE_COMPLETION',
        fromStatus: const {'DRAFT', 'PENDING_PAYMENT'}.contains(fromStatus)
            ? fromStatus
            : 'DRAFT',
        effectiveStatus: 'CANCELLED',
        reasonCode: reasonCode,
        reasonText: reasonText,
        snapshotHash: snapshotHash,
      );
      final duplicate = _idempotent<OrderDispositionResult>(
        'CANCEL_ORDER',
        idempotencyKey,
        requestHash,
        OrderDispositionResult.fromJson,
      );
      if (duplicate != null) return duplicate;
      if (!const {'DRAFT', 'PENDING_PAYMENT'}.contains(fromStatus) ||
          order['payment_status'] != 'UNPAID' ||
          _localCashPaymentCount(orderId) != 0) {
        throw const PosDomainException(
          'ORDER_CANCELLATION_BLOCKED',
          'completed, paid or unknown transaction cannot be cancelled',
        );
      }
      if (basket != null) _verifyPersistedLines(basket);
      final priorVersion = order['record_version']! as int;
      _db.execute(
        '''UPDATE local_order SET status='CANCELLED',record_version=record_version+1
           WHERE tenant_id=? AND order_id=? AND status=? AND payment_status='UNPAID'
             AND record_version=?''',
        [_binding.tenantId, orderId, fromStatus, priorVersion],
      );
      if (_db.updatedRows != 1) {
        throw const PosDomainException(
          'ORDER_VERSION_CONFLICT',
          'cancel conflict',
        );
      }
      final at = occurredAt.toUtc().toIso8601String();
      final version = priorVersion + 1;
      final dispositionId = ulids.next();
      _db.execute(
        '''INSERT INTO local_order_state_history(history_id,tenant_id,order_id,command_id,
           from_status,to_status,aggregate_version,actor_id,reason_code,occurred_at)
           VALUES(?,?,?,?,?,'CANCELLED',?,?,?,?)''',
        [
          ulids.next(),
          _binding.tenantId,
          orderId,
          commandId,
          fromStatus,
          version,
          _binding.cashierId,
          reasonCode,
          at,
        ],
      );
      _insertOrderDisposition(
        dispositionId: dispositionId,
        orderId: orderId,
        shiftId: shiftId,
        businessDate: shift['business_date']! as String,
        dispositionType: 'CANCEL_BEFORE_COMPLETION',
        fromStatus: fromStatus,
        effectiveStatus: 'CANCELLED',
        reasonCode: reasonCode,
        reasonText: reasonText,
        snapshotHash: snapshotHash,
        commandId: commandId,
        idempotencyKey: idempotencyKey,
        requestHash: requestHash,
        aggregateVersion: version,
        at: at,
      );
      localDatabase.checkpoint('order.cancelled');
      final eventId = _appendOutbox(
        stream: 'order.command',
        eventType: 'order.cancelled.v1',
        aggregateId: dispositionId,
        aggregateVersion: 1,
        correlationId: commandId,
        payload: _dispositionPayload(
          dispositionId: dispositionId,
          orderId: orderId,
          shiftId: shiftId,
          businessDate: shift['business_date']! as String,
          dispositionType: 'CANCEL_BEFORE_COMPLETION',
          fromStatus: fromStatus,
          effectiveStatus: 'CANCELLED',
          reasonCode: reasonCode,
          reasonText: reasonText,
          snapshotHash: snapshotHash,
          requestHash: requestHash,
          aggregateVersion: version,
          occurredAt: at,
        ),
        occurredAt: at,
      );
      _audit(
        'ORDER_CANCELLED',
        'ORDER',
        orderId,
        commandId,
        fromStatus,
        'CANCELLED',
        null,
        requestHash,
        at,
      );
      final result = OrderDispositionResult(
        dispositionId: dispositionId,
        orderId: orderId,
        dispositionType: 'CANCEL_BEFORE_COMPLETION',
        fromStatus: fromStatus,
        effectiveStatus: 'CANCELLED',
        requestSha256: requestHash,
        outboxEventId: eventId,
      );
      _saveIdempotency(
        'CANCEL_ORDER',
        commandId,
        idempotencyKey,
        requestHash,
        orderId,
        result.toJson(),
        at,
      );
      return result;
    });
  }
}
