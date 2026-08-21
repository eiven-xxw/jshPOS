import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:sqlite3/sqlite3.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../shift/domain/shift_models.dart';
import '../domain/checkout_models.dart';
import '../domain/exact_quantity.dart';
import '../domain/promoted_order_snapshot_codec.dart';
import '../domain/ulid_generator.dart';

final class PosDomainException implements Exception {
  const PosDomainException(this.code, this.message);
  final String code;
  final String message;
  @override
  String toString() => '$code: $message';
}

final class CheckoutLocalService {
  CheckoutLocalService({
    required this.localDatabase,
    required this.ulids,
    required this.shiftPolicy,
  });

  final PosLocalDatabase localDatabase;
  final UlidGenerator ulids;
  final ShiftPolicy shiftPolicy;
  Database get _db => localDatabase.database;
  TrustedDeviceBinding get _binding => localDatabase.binding;

  ShiftResult openShift({
    required String commandId,
    required String idempotencyKey,
    required String businessDate,
    required int openingCashMinor,
    required int configVersion,
    required DateTime occurredAt,
  }) {
    _requireCommand(commandId, idempotencyKey);
    MoneyRules.requireMinor(openingCashMinor, 'openingCashMinor');
    if (!_isCanonicalBusinessDate(businessDate) || configVersion <= 0) {
      throw const PosDomainException(
        'SHIFT_INPUT_INVALID',
        'business date or config version is invalid',
      );
    }
    final requestHash = _hash([
      _binding.storeId,
      _binding.terminalId,
      _binding.cashierId,
      businessDate,
      _binding.storeTimezone,
      openingCashMinor,
      configVersion,
    ]);
    return localDatabase.transaction(() {
      final duplicate = _idempotent<ShiftResult>(
        'OPEN_SHIFT',
        idempotencyKey,
        requestHash,
        ShiftResult.fromJson,
      );
      if (duplicate != null) return duplicate;
      final shiftId = ulids.next();
      final at = occurredAt.toUtc().toIso8601String();
      _db.execute(
        'INSERT INTO local_shift(shift_id,tenant_id,store_id,terminal_id,cashier_id,cashier_name_snapshot,business_date,store_timezone,config_version,status,currency,opening_cash_minor,theoretical_cash_minor,opened_at,record_version) VALUES(?,?,?,?,?,?,?,?,?,\'OPEN\',\'CNY\',?,?,?,1)',
        [
          shiftId,
          _binding.tenantId,
          _binding.storeId,
          _binding.terminalId,
          _binding.cashierId,
          _binding.cashierName,
          businessDate,
          _binding.storeTimezone,
          configVersion,
          openingCashMinor,
          openingCashMinor,
          at,
        ],
      );
      localDatabase.checkpoint('shift.inserted');
      _appendOutbox(
        stream: 'shift.event',
        eventType: 'shift.opened.v1',
        aggregateId: shiftId,
        aggregateVersion: 1,
        correlationId: commandId,
        payload: {
          'shiftId': shiftId,
          'storeId': _binding.storeId,
          'terminalId': _binding.terminalId,
          'cashierId': _binding.cashierId,
          'businessDate': businessDate,
          'storeTimezone': _binding.storeTimezone,
          'currency': 'CNY',
          'openingCashMinor': openingCashMinor,
        },
        occurredAt: at,
      );
      _audit(
        'SHIFT_OPENED',
        'SHIFT',
        shiftId,
        commandId,
        null,
        'OPEN',
        openingCashMinor,
        requestHash,
        at,
      );
      final result = ShiftResult(
        shiftId: shiftId,
        status: 'OPEN',
        businessDate: businessDate,
        theoreticalCashMinor: openingCashMinor,
        recordVersion: 1,
      );
      _saveIdempotency(
        'OPEN_SHIFT',
        commandId,
        idempotencyKey,
        requestHash,
        shiftId,
        result.toJson(),
        at,
      );
      return result;
    });
  }

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

  CashSaleResult completeCashSale(CashSaleCommand command) {
    _requireCommand(command.commandId, command.idempotencyKey);
    if (!UlidGenerator.isCanonical(command.basket.orderId) ||
        !UlidGenerator.isCanonical(command.shiftId) ||
        command.basket.lines.isEmpty ||
        command.basket.suspended ||
        command.catalogVersion <= 0 ||
        command.priceVersion <= 0) {
      throw const PosDomainException(
        'ORDER_INPUT_INVALID',
        'cash order context is invalid',
      );
    }
    final gross = command.basket.grossAmountMinor;
    MoneyRules.requireMinor(gross, 'receivableAmountMinor');
    MoneyRules.requireMinor(command.tenderedAmountMinor, 'tenderedAmountMinor');
    if (command.tenderedAmountMinor < gross) {
      throw const PosDomainException(
        'CASH_TENDER_INSUFFICIENT',
        'cash tender is below receivable amount',
      );
    }
    final requestHash = command.requestHash(_binding);
    return localDatabase.transaction(() {
      final duplicate = _idempotent<CashSaleResult>(
        'SUBMIT_CASH_ORDER',
        command.idempotencyKey,
        requestHash,
        CashSaleResult.fromJson,
      );
      if (duplicate != null) return duplicate;
      _requireOpenShift(command.shiftId, businessDate: command.businessDate);
      final snapshot = _snapshot(command);
      final snapshotJson = jsonEncode(snapshot);
      final snapshotHash = sha256.convert(utf8.encode(snapshotJson)).toString();
      final existing = _db.select(
        'SELECT * FROM local_order WHERE tenant_id=? AND order_id=?',
        [_binding.tenantId, command.basket.orderId],
      );
      final at = command.occurredAt.toUtc().toIso8601String();
      int submittedVersion;
      int completedVersion;
      if (existing.isEmpty) {
        _insertCompletedOrder(
          command,
          snapshotJson,
          snapshotHash,
          requestHash,
          at,
        );
        _insertLines(command.basket);
        _insertStateHistory(
          command,
          startingVersion: 1,
          includeDraft: true,
          at: at,
        );
        submittedVersion = 2;
        completedVersion = 4;
      } else {
        if (existing.single['status'] != 'DRAFT' ||
            existing.single['draft_disposition'] != 'ACTIVE' ||
            !_matchesDraftContext(
              existing.single,
              command.shiftId,
              command.basket.localOrderNo,
              businessDate: command.businessDate,
            )) {
          throw const PosDomainException(
            'ORDER_STATE_CONFLICT',
            'existing order is not an active draft in the same frozen shift context',
          );
        }
        _verifyPersistedLines(command.basket);
        final priorVersion = existing.single['record_version']! as int;
        _db.execute(
          'UPDATE local_order SET status=\'COMPLETED\',payment_status=\'PAID\',gross_amount_minor=?,receivable_amount_minor=?,received_amount_minor=?,catalog_version=?,price_version=?,industry_template_version=?,snapshot_schema_version=1,snapshot_json=?,snapshot_sha256=?,idempotency_key=?,request_sha256=?,record_version=record_version+3 WHERE tenant_id=? AND order_id=? AND status=\'DRAFT\' AND draft_disposition=\'ACTIVE\' AND record_version=?',
          [
            gross,
            gross,
            gross,
            command.catalogVersion,
            command.priceVersion,
            command.industryTemplateVersion,
            snapshotJson,
            snapshotHash,
            command.idempotencyKey,
            requestHash,
            _binding.tenantId,
            command.basket.orderId,
            priorVersion,
          ],
        );
        if (_db.updatedRows != 1) {
          throw const PosDomainException(
            'ORDER_VERSION_CONFLICT',
            'order completion conflict',
          );
        }
        _insertStateHistory(
          command,
          startingVersion: priorVersion + 1,
          includeDraft: false,
          at: at,
        );
        submittedVersion = priorVersion + 1;
        completedVersion = priorVersion + 3;
      }
      localDatabase.checkpoint('order.snapshot');
      final paymentId = ulids.next();
      final change = command.tenderedAmountMinor - gross;
      _db.execute(
        'INSERT INTO local_cash_payment(payment_id,tenant_id,order_id,shift_id,status,currency,receivable_amount_minor,tendered_amount_minor,change_amount_minor,net_amount_minor,occurred_at) VALUES(?,?,?,?,\'SUCCEEDED\',\'CNY\',?,?,?,?,?)',
        [
          paymentId,
          _binding.tenantId,
          command.basket.orderId,
          command.shiftId,
          gross,
          command.tenderedAmountMinor,
          change,
          gross,
          at,
        ],
      );
      localDatabase.checkpoint('cash.payment');
      _db.execute(
        'INSERT INTO local_cash_ledger(ledger_id,tenant_id,shift_id,order_id,payment_id,movement_type,signed_amount_minor,currency,business_date,occurred_at) VALUES(?,?,?,?,?,\'SALE_RECEIPT\',?,\'CNY\',?,?)',
        [
          ulids.next(),
          _binding.tenantId,
          command.shiftId,
          command.basket.orderId,
          paymentId,
          gross,
          command.businessDate,
          at,
        ],
      );
      _db.execute(
        'UPDATE local_shift SET theoretical_cash_minor=theoretical_cash_minor+?,record_version=record_version+1 WHERE tenant_id=? AND shift_id=? AND status=\'OPEN\'',
        [gross, _binding.tenantId, command.shiftId],
      );
      if (_db.updatedRows != 1) {
        throw const PosDomainException(
          'SHIFT_STATE_CONFLICT',
          'shift changed during cash sale',
        );
      }
      localDatabase.checkpoint('cash.ledger');
      final printJobId = ulids.next();
      _db.execute(
        'INSERT INTO local_print_job(print_job_id,tenant_id,order_id,status,template_version,payload_sha256,created_at) VALUES(?,?,?,\'PENDING\',?,?,?)',
        [
          printJobId,
          _binding.tenantId,
          command.basket.orderId,
          command.industryTemplateVersion,
          snapshotHash,
          at,
        ],
      );
      _freezeReceiptDocument(
        orderId: command.basket.orderId,
        printJobId: printJobId,
        templateVersion: command.industryTemplateVersion,
        at: at,
      );
      localDatabase.checkpoint('print.queued');
      final submittedEvent = _appendOutbox(
        stream: 'order.command',
        eventType: 'order.submitted.v1',
        aggregateId: command.basket.orderId,
        aggregateVersion: submittedVersion,
        correlationId: command.commandId,
        payload: {
          'orderId': command.basket.orderId,
          'shiftId': command.shiftId,
          'receivableAmountMinor': gross,
          'snapshotHash': 'sha256:$snapshotHash',
        },
        occurredAt: at,
      );
      _appendOutbox(
        stream: 'order.command',
        eventType: 'cash.received.v1',
        aggregateId: paymentId,
        aggregateVersion: 1,
        correlationId: command.commandId,
        payload: {
          'paymentId': paymentId,
          'orderId': command.basket.orderId,
          'shiftId': command.shiftId,
          'currency': 'CNY',
          'tenderedAmountMinor': command.tenderedAmountMinor,
          'changeAmountMinor': change,
          'netAmountMinor': gross,
        },
        occurredAt: at,
      );
      _appendOutbox(
        stream: 'order.command',
        eventType: 'order.completed.v1',
        aggregateId: command.basket.orderId,
        aggregateVersion: completedVersion,
        correlationId: command.commandId,
        payload: {
          'orderId': command.basket.orderId,
          'shiftId': command.shiftId,
          'paymentId': paymentId,
          'businessDate': command.businessDate,
          'currency': 'CNY',
          'receivableAmountMinor': gross,
          'aggregateVersion': completedVersion,
          'snapshotHash': 'sha256:$snapshotHash',
        },
        occurredAt: at,
      );
      localDatabase.checkpoint('outbox.appended');
      _audit(
        'CASH_ORDER_COMPLETED',
        'ORDER',
        command.basket.orderId,
        command.commandId,
        'DRAFT',
        'COMPLETED',
        gross,
        requestHash,
        at,
      );
      localDatabase.checkpoint('audit.appended');
      final result = CashSaleResult(
        orderId: command.basket.orderId,
        paymentId: paymentId,
        receivableAmountMinor: gross,
        tenderedAmountMinor: command.tenderedAmountMinor,
        changeAmountMinor: change,
        snapshotHash: 'sha256:$snapshotHash',
        outboxEventId: submittedEvent,
      );
      _saveIdempotency(
        'SUBMIT_CASH_ORDER',
        command.commandId,
        command.idempotencyKey,
        requestHash,
        command.basket.orderId,
        result.toJson(),
        at,
      );
      localDatabase.checkpoint('idempotency.saved');
      return result;
    });
  }

  /// POS-006 将促销快照、订单、现金、班次效果和 Outbox 原子提交。
  PromotedCashSaleResult completePromotedCashSale(
    PromotedCashSaleCommand command,
  ) {
    _requireCommand(command.commandId, command.idempotencyKey);
    if (!UlidGenerator.isCanonical(command.basket.orderId) ||
        !UlidGenerator.isCanonical(command.shiftId) ||
        command.basket.lines.isEmpty ||
        command.basket.suspended ||
        command.catalogVersion <= 0 ||
        command.priceVersion <= 0 ||
        !_isCanonicalBusinessDate(command.businessDate)) {
      throw const PosDomainException(
        'ORDER_INPUT_INVALID',
        'promoted cash order context is invalid',
      );
    }
    MoneyRules.requireMinor(
      command.receivableAmountMinor,
      'receivableAmountMinor',
    );
    MoneyRules.requireMinor(command.tenderedAmountMinor, 'tenderedAmountMinor');
    if (command.tenderedAmountMinor < command.receivableAmountMinor) {
      throw const PosDomainException(
        'CASH_TENDER_INSUFFICIENT',
        'cash tender is below promoted receivable amount',
      );
    }
    final requestHash = command.requestHash(_binding);
    final basketInputHash = command.basketInputHash(_binding);
    return localDatabase.transaction(() {
      final duplicate = _idempotent<PromotedCashSaleResult>(
        'SUBMIT_PROMOTED_CASH_ORDER',
        command.idempotencyKey,
        requestHash,
        PromotedCashSaleResult.fromJson,
      );
      if (duplicate != null) return duplicate;
      _requireOpenShift(command.shiftId, businessDate: command.businessDate);
      _verifyPromotionSettlement(command);
      localDatabase.checkpoint('promotion.inputs.verified');

      final promotionDocument = _promotionSnapshot(command);
      final promotionJson = PromotedOrderSnapshotCodec.canonicalJson(
        promotionDocument,
      );
      final promotionHash = sha256
          .convert(utf8.encode(promotionJson))
          .toString();
      final orderSnapshot = PromotedOrderSnapshotCodec.document(
        command: command,
        binding: _binding,
        promotionSnapshotSha256: promotionHash,
      );
      final orderSnapshotJson = PromotedOrderSnapshotCodec.canonicalJson(
        orderSnapshot,
      );
      final orderSnapshotHash = sha256
          .convert(utf8.encode(orderSnapshotJson))
          .toString();
      final existing = _db.select(
        'SELECT * FROM local_order WHERE tenant_id=? AND order_id=?',
        [_binding.tenantId, command.basket.orderId],
      );
      final at = command.occurredAt.toUtc().toIso8601String();
      int submittedVersion;
      int completedVersion;
      if (existing.isEmpty) {
        _insertPromotedCompletedOrder(
          command,
          orderSnapshotJson,
          orderSnapshotHash,
          requestHash,
          at,
        );
        _insertPromotedLines(command);
        _insertPromotedStateHistory(
          command,
          startingVersion: 1,
          includeDraft: true,
          at: at,
        );
        submittedVersion = 2;
        completedVersion = 4;
      } else {
        if (existing.single['status'] != 'DRAFT' ||
            existing.single['draft_disposition'] != 'ACTIVE' ||
            !_matchesDraftContext(
              existing.single,
              command.shiftId,
              command.basket.localOrderNo,
              businessDate: command.businessDate,
            )) {
          throw const PosDomainException(
            'ORDER_STATE_CONFLICT',
            'existing order is not an active draft in the frozen context',
          );
        }
        _verifyPersistedLines(command.basket);
        _applyPromotedLineAmounts(command);
        final priorVersion = existing.single['record_version']! as int;
        _db.execute(
          'UPDATE local_order SET status=\'COMPLETED\',payment_status=\'PAID\',gross_amount_minor=?,discount_amount_minor=?,surcharge_amount_minor=?,receivable_amount_minor=?,received_amount_minor=?,catalog_version=?,price_version=?,industry_template_version=?,snapshot_schema_version=2,snapshot_json=?,snapshot_sha256=?,idempotency_key=?,request_sha256=?,record_version=record_version+3 WHERE tenant_id=? AND order_id=? AND status=\'DRAFT\' AND draft_disposition=\'ACTIVE\' AND record_version=?',
          [
            command.grossAmountMinor,
            command.discountAmountMinor,
            command.surchargeAmountMinor,
            command.receivableAmountMinor,
            command.receivableAmountMinor,
            command.catalogVersion,
            command.priceVersion,
            command.industryTemplateVersion,
            orderSnapshotJson,
            orderSnapshotHash,
            command.idempotencyKey,
            requestHash,
            _binding.tenantId,
            command.basket.orderId,
            priorVersion,
          ],
        );
        if (_db.updatedRows != 1) {
          throw const PosDomainException(
            'ORDER_VERSION_CONFLICT',
            'promoted order completion conflict',
          );
        }
        _insertPromotedStateHistory(
          command,
          startingVersion: priorVersion + 1,
          includeDraft: false,
          at: at,
        );
        submittedVersion = priorVersion + 1;
        completedVersion = priorVersion + 3;
      }
      localDatabase.checkpoint('promoted.order.snapshot');

      _insertPromotionSnapshot(command, promotionHash, at);
      localDatabase.checkpoint('promotion.snapshot');
      _db.execute(
        'INSERT INTO local_checkout_settlement(settlement_id,tenant_id,order_id,promotion_snapshot_id,quote_id,store_id,terminal_id,shift_id,business_date,package_version,quote_fingerprint,settlement_fingerprint,manual_event_refs_json,basket_input_sha256,request_sha256,order_snapshot_sha256,promotion_snapshot_sha256,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,status,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,\'COMMITTED\',?)',
        [
          command.commandId,
          _binding.tenantId,
          command.basket.orderId,
          command.promotionSnapshotId,
          command.quoteId,
          _binding.storeId,
          _binding.terminalId,
          command.shiftId,
          command.businessDate,
          command.packageVersion,
          command.quoteFingerprint,
          command.settlementFingerprint,
          jsonEncode(command.manualEventRefs),
          basketInputHash,
          requestHash,
          orderSnapshotHash,
          promotionHash,
          command.grossAmountMinor,
          command.discountAmountMinor,
          command.surchargeAmountMinor,
          command.receivableAmountMinor,
          at,
        ],
      );
      localDatabase.checkpoint('checkout.settlement');

      final paymentId = ulids.next();
      final change =
          command.tenderedAmountMinor - command.receivableAmountMinor;
      _db.execute(
        'INSERT INTO local_cash_payment(payment_id,tenant_id,order_id,shift_id,status,currency,receivable_amount_minor,tendered_amount_minor,change_amount_minor,net_amount_minor,occurred_at) VALUES(?,?,?,?,\'SUCCEEDED\',\'CNY\',?,?,?,?,?)',
        [
          paymentId,
          _binding.tenantId,
          command.basket.orderId,
          command.shiftId,
          command.receivableAmountMinor,
          command.tenderedAmountMinor,
          change,
          command.receivableAmountMinor,
          at,
        ],
      );
      localDatabase.checkpoint('promoted.cash.payment');
      _db.execute(
        'INSERT INTO local_cash_ledger(ledger_id,tenant_id,shift_id,order_id,payment_id,movement_type,signed_amount_minor,currency,business_date,occurred_at) VALUES(?,?,?,?,?,\'SALE_RECEIPT\',?,\'CNY\',?,?)',
        [
          ulids.next(),
          _binding.tenantId,
          command.shiftId,
          command.basket.orderId,
          paymentId,
          command.receivableAmountMinor,
          command.businessDate,
          at,
        ],
      );
      _db.execute(
        'UPDATE local_shift SET theoretical_cash_minor=theoretical_cash_minor+?,record_version=record_version+1 WHERE tenant_id=? AND shift_id=? AND status=\'OPEN\'',
        [command.receivableAmountMinor, _binding.tenantId, command.shiftId],
      );
      if (_db.updatedRows != 1) {
        throw const PosDomainException(
          'SHIFT_STATE_CONFLICT',
          'shift changed during promoted cash sale',
        );
      }
      localDatabase.checkpoint('promoted.cash.ledger');
      final printJobId = ulids.next();
      _db.execute(
        'INSERT INTO local_print_job(print_job_id,tenant_id,order_id,status,template_version,payload_sha256,created_at) VALUES(?,?,?,\'PENDING\',?,?,?)',
        [
          printJobId,
          _binding.tenantId,
          command.basket.orderId,
          command.industryTemplateVersion,
          orderSnapshotHash,
          at,
        ],
      );
      final receipt = _freezeReceiptDocument(
        orderId: command.basket.orderId,
        printJobId: printJobId,
        templateVersion: command.industryTemplateVersion,
        at: at,
      );
      final submittedEvent = _appendOutbox(
        stream: 'order.command',
        eventType: 'order.submitted.v2',
        aggregateId: command.basket.orderId,
        aggregateVersion: submittedVersion,
        correlationId: command.commandId,
        payload: {
          'schemaVersion': '2.0',
          'orderId': command.basket.orderId,
          'localOrderNo': command.basket.localOrderNo,
          'storeId': _binding.storeId,
          'terminalId': _binding.terminalId,
          'shiftId': command.shiftId,
          'cashierId': _binding.cashierId,
          'businessDate': command.businessDate,
          'storeTimezone': _binding.storeTimezone,
          'catalogVersion': command.catalogVersion,
          'priceVersion': command.priceVersion,
          'industryTemplateVersion': command.industryTemplateVersion,
          'grossAmountMinor': command.grossAmountMinor,
          'discountAmountMinor': command.discountAmountMinor,
          'surchargeAmountMinor': command.surchargeAmountMinor,
          'receivableAmountMinor': command.receivableAmountMinor,
          'tenderedAmountMinor': command.tenderedAmountMinor,
          'promotionSnapshotId': command.promotionSnapshotId,
          'quoteId': command.quoteId,
          'promotionEngineVersion': 'promotion-engine-1.0.0',
          'promotionSnapshotHash': 'sha256:$promotionHash',
          'quoteFingerprint': command.quoteFingerprint,
          'settlementFingerprint': command.settlementFingerprint,
          'packageVersion': command.packageVersion,
          'manualEventRefs': command.manualEventRefs,
          'orderSnapshotHash': 'sha256:$orderSnapshotHash',
          'lines': command.lines.map((line) => line.toSnapshot()).toList(),
        },
        occurredAt: at,
      );
      _appendOutbox(
        stream: 'order.command',
        eventType: 'cash.received.v1',
        aggregateId: paymentId,
        aggregateVersion: 1,
        correlationId: command.commandId,
        payload: {
          'paymentId': paymentId,
          'orderId': command.basket.orderId,
          'shiftId': command.shiftId,
          'currency': 'CNY',
          'tenderedAmountMinor': command.tenderedAmountMinor,
          'changeAmountMinor': change,
          'netAmountMinor': command.receivableAmountMinor,
        },
        occurredAt: at,
      );
      _appendOutbox(
        stream: 'order.command',
        eventType: 'order.completed.v2',
        aggregateId: command.basket.orderId,
        aggregateVersion: completedVersion,
        correlationId: command.commandId,
        payload: {
          'schemaVersion': '2.0',
          'orderId': command.basket.orderId,
          'shiftId': command.shiftId,
          'paymentId': paymentId,
          'businessDate': command.businessDate,
          'currency': 'CNY',
          'grossAmountMinor': command.grossAmountMinor,
          'discountAmountMinor': command.discountAmountMinor,
          'surchargeAmountMinor': command.surchargeAmountMinor,
          'receivableAmountMinor': command.receivableAmountMinor,
          'promotionSnapshotId': command.promotionSnapshotId,
          'promotionSnapshotHash': 'sha256:$promotionHash',
          'quoteFingerprint': command.quoteFingerprint,
          'settlementFingerprint': command.settlementFingerprint,
          'packageVersion': command.packageVersion,
          'aggregateVersion': completedVersion,
          'orderSnapshotHash': 'sha256:$orderSnapshotHash',
        },
        occurredAt: at,
      );
      _appendReceiptFrozenEvent(
        receipt: receipt,
        printJobId: printJobId,
        aggregateVersion: completedVersion,
        correlationId: command.commandId,
        occurredAt: at,
      );
      localDatabase.checkpoint('promoted.outbox.appended');
      _audit(
        'PROMOTED_CASH_ORDER_COMPLETED',
        'ORDER',
        command.basket.orderId,
        command.commandId,
        'DRAFT',
        'COMPLETED',
        command.receivableAmountMinor,
        requestHash,
        at,
      );
      localDatabase.checkpoint('promoted.audit.appended');
      final result = PromotedCashSaleResult(
        orderId: command.basket.orderId,
        paymentId: paymentId,
        promotionSnapshotId: command.promotionSnapshotId,
        receivableAmountMinor: command.receivableAmountMinor,
        tenderedAmountMinor: command.tenderedAmountMinor,
        changeAmountMinor: change,
        orderSnapshotHash: 'sha256:$orderSnapshotHash',
        promotionSnapshotHash: 'sha256:$promotionHash',
        outboxEventId: submittedEvent,
      );
      _saveIdempotency(
        'SUBMIT_PROMOTED_CASH_ORDER',
        command.commandId,
        command.idempotencyKey,
        requestHash,
        command.basket.orderId,
        result.toJson(),
        at,
      );
      localDatabase.checkpoint('promoted.idempotency.saved');
      return result;
    });
  }

  /// 创建受权补打请求；只形成可同步的软件事实，不调用真实打印机。
  ReceiptReprintResult requestReceiptReprint({
    required String commandId,
    required String idempotencyKey,
    required String orderId,
    required String reasonCode,
    required String reasonText,
    required String authorizationRef,
    required DateTime occurredAt,
  }) {
    _requireCommand(commandId, idempotencyKey);
    if (!RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(orderId) ||
        !RegExp(r'^[A-Z][A-Z0-9_]{1,31}$').hasMatch(reasonCode) ||
        reasonCode == 'ORDER_COMPLETED' ||
        reasonText.trim().isEmpty ||
        reasonText.trim().length > 256 ||
        authorizationRef.length < 16 ||
        authorizationRef.length > 128) {
      throw const PosDomainException(
        'RECEIPT_REPRINT_INVALID',
        'receipt reprint input is invalid',
      );
    }
    final at = occurredAt.toUtc().toIso8601String();
    final requestHash = _hash([
      orderId,
      reasonCode,
      reasonText.trim(),
      authorizationRef,
      _binding.cashierId,
    ]);
    final replay = _idempotent<ReceiptReprintResult>(
      'REQUEST_RECEIPT_REPRINT',
      idempotencyKey,
      requestHash,
      ReceiptReprintResult.fromJson,
    );
    if (replay != null) {
      return replay;
    }
    return localDatabase.transaction(() {
      final rows = _db.select(
        '''SELECT d.document_id,d.content_sha256,d.template_version,d.semantic_payload_json,p.print_job_id
           FROM local_receipt_document d
           JOIN local_order o ON o.tenant_id=d.tenant_id AND o.order_id=d.order_id
           JOIN local_print_job p ON p.tenant_id=d.tenant_id AND p.order_id=d.order_id
           WHERE d.tenant_id=? AND o.store_id=? AND o.terminal_id=?
             AND d.order_id=? AND o.status='COMPLETED' ''',
        [_binding.tenantId, _binding.storeId, _binding.terminalId, orderId],
      );
      if (rows.length != 1) {
        throw const PosDomainException(
          'RECEIPT_NOT_FOUND',
          'receipt document does not exist in trusted terminal scope',
        );
      }
      final row = rows.single;
      final semanticPayloadJson = row['semantic_payload_json']! as String;
      if (sha256.convert(utf8.encode(semanticPayloadJson)).toString() !=
          row['content_sha256']) {
        throw const PosDomainException(
          'RECEIPT_HASH_MISMATCH',
          'receipt semantic payload digest does not match the frozen digest',
        );
      }
      final reprintNo =
          (_db.select(
                "SELECT COALESCE(MAX(reprint_no),0) n FROM local_print_request WHERE tenant_id=? AND order_id=? AND request_kind='REPRINT'",
                [_binding.tenantId, orderId],
              ).single['n']!
              as int) +
          1;
      if (reprintNo > 999) {
        throw const PosDomainException(
          'RECEIPT_REPRINT_LIMIT_REACHED',
          'receipt reprint sequence exceeds the audited limit',
        );
      }
      final requestId = ulids.next();
      _db.execute(
        '''INSERT INTO local_print_request(print_request_id,tenant_id,print_job_id,order_id,document_id,
           request_kind,reprint_no,requested_by,requested_by_name,authorization_ref,reason_code,reason_text,
           idempotency_key,request_sha256,document_sha256,execution_status,adapter_evidence,requested_at)
           VALUES(?,?,?,?,?,'REPRINT',?,?,?,?,?,?,?,?,?,'BLOCKED_EXTERNAL','BLOCKED_REAL_PRINTER',?)''',
        [
          requestId,
          _binding.tenantId,
          row['print_job_id'],
          orderId,
          row['document_id'],
          reprintNo,
          _binding.cashierId,
          _binding.cashierName,
          authorizationRef,
          reasonCode,
          reasonText.trim(),
          idempotencyKey,
          requestHash,
          row['content_sha256'],
          at,
        ],
      );
      localDatabase.checkpoint('reprint.requested');
      final eventId = _appendOutbox(
        stream: 'order.command',
        eventType: 'receipt.reprint-requested.v1',
        aggregateId: requestId,
        aggregateVersion: 1,
        correlationId: commandId,
        payload: {
          'printRequestId': requestId,
          'printJobId': row['print_job_id'],
          'documentId': row['document_id'],
          'orderId': orderId,
          'requestKind': 'REPRINT',
          'reprintNo': reprintNo,
          'storeId': _binding.storeId,
          'terminalId': _binding.terminalId,
          'cashierId': _binding.cashierId,
          'authorizationRef': authorizationRef,
          'reasonCode': reasonCode,
          'reasonText': reasonText.trim(),
          'requestSha256': requestHash,
          'documentSha256': row['content_sha256'],
          'executionStatus': 'BLOCKED_EXTERNAL',
          'requestedAt': at,
        },
        occurredAt: at,
      );
      _audit(
        'RECEIPT_REPRINT_REQUESTED',
        'PRINT_REQUEST',
        requestId,
        commandId,
        null,
        'BLOCKED_EXTERNAL',
        null,
        requestHash,
        at,
      );
      final result = ReceiptReprintResult(
        printRequestId: requestId,
        orderId: orderId,
        documentId: row['document_id']! as String,
        reprintNo: reprintNo,
        documentSha256: row['content_sha256']! as String,
        executionStatus: 'BLOCKED_EXTERNAL',
        outboxEventId: eventId,
        duplicate: false,
      );
      _saveIdempotency(
        'REQUEST_RECEIPT_REPRINT',
        commandId,
        idempotencyKey,
        requestHash,
        requestId,
        result.toJson(),
        at,
      );
      return result;
    });
  }

  /// 在一个 SQLite 事务中追加非销售现金事实、班次余额、审计和 Outbox。
  ShiftOperationResult recordShiftCashMovement({
    required String commandId,
    required String idempotencyKey,
    required String shiftId,
    required ShiftCashMovementType movementType,
    required int amountMinor,
    required String reasonCode,
    required String reasonText,
    required String authorizationRef,
    required int expectedVersion,
    required DateTime occurredAt,
  }) {
    _requireCommand(commandId, idempotencyKey);
    MoneyRules.requireMinor(amountMinor, 'amountMinor');
    if (amountMinor <= 0 ||
        expectedVersion <= 0 ||
        !RegExp(r'^[A-Z][A-Z0-9_]{1,31}$').hasMatch(reasonCode) ||
        reasonText.trim().isEmpty ||
        reasonText.length > 256 ||
        !RegExp(r'^[A-Za-z0-9._:-]{16,128}$').hasMatch(authorizationRef)) {
      throw const PosDomainException(
        'SHIFT_CASH_INPUT_INVALID',
        'cash movement amount, reason, authorization or version is invalid',
      );
    }
    final signed = movementType.signed(amountMinor);
    final requestHash = _hash([
      shiftId,
      movementType.wireCode,
      amountMinor,
      reasonCode,
      reasonText,
      authorizationRef,
      expectedVersion,
    ]);
    return localDatabase.transaction(() {
      final duplicate = _idempotent<ShiftOperationResult>(
        'RECORD_SHIFT_CASH_MOVEMENT',
        idempotencyKey,
        requestHash,
        ShiftOperationResult.fromJson,
      );
      if (duplicate != null) return duplicate;
      final shift = _requireOpenShift(shiftId);
      if (shift['record_version'] != expectedVersion) {
        throw const PosDomainException(
          'SHIFT_STATE_CONFLICT',
          'cash movement shift version conflict',
        );
      }
      final current = shift['theoretical_cash_minor']! as int;
      final next = current + signed;
      MoneyRules.requireMinor(next, 'theoreticalCashMinor');
      final movementId = ulids.next();
      final version = expectedVersion + 1;
      final at = occurredAt.toUtc().toIso8601String();
      _db.execute(
        'INSERT INTO local_shift_cash_movement(movement_id,tenant_id,shift_id,store_id,terminal_id,cashier_id,business_date,movement_type,signed_amount_minor,currency,reason_code,reason_text,authorization_ref,command_id,request_sha256,shift_version,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,\'CNY\',?,?,?,?,?,?,?)',
        [
          movementId,
          _binding.tenantId,
          shiftId,
          _binding.storeId,
          _binding.terminalId,
          _binding.cashierId,
          shift['business_date'],
          movementType.wireCode,
          signed,
          reasonCode,
          reasonText.trim(),
          authorizationRef,
          commandId,
          requestHash,
          version,
          at,
        ],
      );
      _db.execute(
        'UPDATE local_shift SET theoretical_cash_minor=?,record_version=record_version+1 WHERE tenant_id=? AND shift_id=? AND status=\'OPEN\' AND record_version=?',
        [next, _binding.tenantId, shiftId, expectedVersion],
      );
      if (_db.updatedRows != 1) {
        throw const PosDomainException(
          'SHIFT_STATE_CONFLICT',
          'concurrent cash movement conflict',
        );
      }
      localDatabase.checkpoint('shift.cash-movement.persisted');
      _appendOutbox(
        stream: 'shift.event',
        eventType: 'shift.cash-movement.recorded.v1',
        aggregateId: shiftId,
        aggregateVersion: version,
        correlationId: commandId,
        payload: {
          'movementId': movementId,
          'shiftId': shiftId,
          'storeId': _binding.storeId,
          'terminalId': _binding.terminalId,
          'cashierId': _binding.cashierId,
          'businessDate': shift['business_date'],
          'movementType': movementType.wireCode,
          'amountMinor': amountMinor,
          'signedAmountMinor': signed,
          'currency': 'CNY',
          'reasonCode': reasonCode,
          'reasonText': reasonText.trim(),
          'authorizationRef': authorizationRef,
          'expectedVersion': expectedVersion,
        },
        occurredAt: at,
      );
      _audit(
        'SHIFT_CASH_${movementType.wireCode}',
        'SHIFT',
        shiftId,
        commandId,
        'OPEN',
        'OPEN',
        signed,
        requestHash,
        at,
      );
      final result = ShiftOperationResult(
        operationId: movementId,
        shiftId: shiftId,
        operationType: movementType.wireCode,
        signedAmountMinor: signed,
        theoreticalCashMinor: next,
        recordVersion: version,
        deviceExecutionStatus: 'NOT_APPLICABLE',
      );
      _saveIdempotency(
        'RECORD_SHIFT_CASH_MOVEMENT',
        commandId,
        idempotencyKey,
        requestHash,
        shiftId,
        result.toJson(),
        at,
      );
      return result;
    });
  }

  /// 钱箱外设未解阻：只追加请求事实并固定失败关闭，不下发 MethodChannel。
  ShiftOperationResult requestNoSaleDrawer({
    required String commandId,
    required String idempotencyKey,
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required String authorizationRef,
    required int expectedVersion,
    required DateTime occurredAt,
  }) {
    _requireCommand(commandId, idempotencyKey);
    if (expectedVersion <= 0 ||
        !RegExp(r'^[A-Z][A-Z0-9_]{1,31}$').hasMatch(reasonCode) ||
        reasonText.trim().isEmpty ||
        reasonText.length > 256 ||
        !RegExp(r'^[A-Za-z0-9._:-]{16,128}$').hasMatch(authorizationRef)) {
      throw const PosDomainException(
        'DRAWER_REQUEST_INPUT_INVALID',
        'drawer reason, authorization or version is invalid',
      );
    }
    final requestHash = _hash([
      shiftId,
      reasonCode,
      reasonText,
      authorizationRef,
      expectedVersion,
    ]);
    return localDatabase.transaction(() {
      final duplicate = _idempotent<ShiftOperationResult>(
        'REQUEST_NO_SALE_DRAWER',
        idempotencyKey,
        requestHash,
        ShiftOperationResult.fromJson,
      );
      if (duplicate != null) return duplicate;
      final shift = _requireOpenShift(shiftId);
      if (shift['record_version'] != expectedVersion) {
        throw const PosDomainException(
          'SHIFT_STATE_CONFLICT',
          'drawer request shift version conflict',
        );
      }
      final eventId = ulids.next();
      final version = expectedVersion + 1;
      final at = occurredAt.toUtc().toIso8601String();
      _db.execute(
        'INSERT INTO local_drawer_event(drawer_event_id,tenant_id,shift_id,store_id,terminal_id,cashier_id,business_date,event_type,reason_code,reason_text,authorization_ref,device_execution_status,command_id,request_sha256,shift_version,occurred_at) VALUES(?,?,?,?,?,?,?,\'NO_SALE_OPEN_REQUESTED\',?,?,?,\'BLOCKED_EXTERNAL\',?,?,?,?)',
        [
          eventId,
          _binding.tenantId,
          shiftId,
          _binding.storeId,
          _binding.terminalId,
          _binding.cashierId,
          shift['business_date'],
          reasonCode,
          reasonText.trim(),
          authorizationRef,
          commandId,
          requestHash,
          version,
          at,
        ],
      );
      _db.execute(
        'UPDATE local_shift SET record_version=record_version+1 WHERE tenant_id=? AND shift_id=? AND status=\'OPEN\' AND record_version=?',
        [_binding.tenantId, shiftId, expectedVersion],
      );
      if (_db.updatedRows != 1) {
        throw const PosDomainException(
          'SHIFT_STATE_CONFLICT',
          'concurrent drawer request conflict',
        );
      }
      localDatabase.checkpoint('shift.drawer-request.persisted');
      _appendOutbox(
        stream: 'shift.event',
        eventType: 'shift.drawer-requested.v1',
        aggregateId: shiftId,
        aggregateVersion: version,
        correlationId: commandId,
        payload: {
          'drawerEventId': eventId,
          'shiftId': shiftId,
          'storeId': _binding.storeId,
          'terminalId': _binding.terminalId,
          'cashierId': _binding.cashierId,
          'businessDate': shift['business_date'],
          'reasonCode': reasonCode,
          'reasonText': reasonText.trim(),
          'authorizationRef': authorizationRef,
          'deviceExecutionStatus': 'BLOCKED_EXTERNAL',
          'expectedVersion': expectedVersion,
        },
        occurredAt: at,
      );
      _audit(
        'NO_SALE_DRAWER_REQUESTED',
        'SHIFT',
        shiftId,
        commandId,
        'OPEN',
        'OPEN',
        null,
        requestHash,
        at,
      );
      final result = ShiftOperationResult(
        operationId: eventId,
        shiftId: shiftId,
        operationType: 'NO_SALE_OPEN_REQUESTED',
        theoreticalCashMinor: shift['theoretical_cash_minor']! as int,
        recordVersion: version,
        deviceExecutionStatus: 'BLOCKED_EXTERNAL',
      );
      _saveIdempotency(
        'REQUEST_NO_SALE_DRAWER',
        commandId,
        idempotencyKey,
        requestHash,
        shiftId,
        result.toJson(),
        at,
      );
      return result;
    });
  }

  ShiftCloseApproval approveShiftDifference({
    required String commandId,
    required String idempotencyKey,
    required String shiftId,
    required int actualCashMinor,
    required int expectedVersion,
    required String reasonCode,
    required String reasonText,
    required SupervisorSession supervisor,
    required DateTime occurredAt,
  }) {
    _requireCommand(commandId, idempotencyKey);
    MoneyRules.requireMinor(actualCashMinor, 'actualCashMinor');
    supervisor.validate(_binding.cashierId, occurredAt);
    if (!RegExp(r'^[A-Z][A-Z0-9_]{1,31}$').hasMatch(reasonCode) ||
        reasonText.trim().isEmpty ||
        reasonText.length > 256) {
      throw const PosDomainException(
        'SHIFT_APPROVAL_INPUT_INVALID',
        'approval reason is invalid',
      );
    }
    final requestHash = _hash([
      shiftId,
      actualCashMinor,
      expectedVersion,
      reasonCode,
      reasonText,
      supervisor.supervisorId,
      supervisor.authProofRef,
    ]);
    return localDatabase.transaction(() {
      final duplicate = _idempotent<ShiftCloseApproval>(
        'APPROVE_SHIFT_DIFFERENCE',
        idempotencyKey,
        requestHash,
        ShiftCloseApproval.fromJson,
      );
      if (duplicate != null) return duplicate;
      final rows = _db.select(
        'SELECT * FROM local_shift WHERE tenant_id=? AND store_id=? AND terminal_id=? AND shift_id=?',
        [_binding.tenantId, _binding.storeId, _binding.terminalId, shiftId],
      );
      if (rows.length != 1 ||
          rows.single['status'] != 'OPEN' ||
          rows.single['record_version'] != expectedVersion) {
        throw const PosDomainException(
          'SHIFT_STATE_CONFLICT',
          'shift approval state or version conflict',
        );
      }
      final ledger = _cashLedgerTotal(shiftId);
      final theoretical = (rows.single['opening_cash_minor']! as int) + ledger;
      final difference = actualCashMinor - theoretical;
      if (difference.abs() <= shiftPolicy.cashDifferenceApprovalMinor) {
        throw const PosDomainException(
          'SHIFT_APPROVAL_NOT_REQUIRED',
          'difference is within the configured threshold',
        );
      }
      final approvalId = ulids.next();
      final at = occurredAt.toUtc().toIso8601String();
      _db.execute(
        'INSERT INTO local_shift_approval(approval_id,tenant_id,shift_id,approver_id,approver_name_snapshot,reason_code,reason_text,theoretical_cash_minor,actual_cash_minor,difference_minor,expected_shift_version,auth_proof_ref,authenticated_at,command_id,request_sha256,status,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,\'APPROVED\',?)',
        [
          approvalId,
          _binding.tenantId,
          shiftId,
          supervisor.supervisorId,
          supervisor.supervisorName,
          reasonCode,
          reasonText,
          theoretical,
          actualCashMinor,
          difference,
          expectedVersion,
          supervisor.authProofRef,
          supervisor.authenticatedAt.toUtc().toIso8601String(),
          commandId,
          requestHash,
          at,
        ],
      );
      localDatabase.checkpoint('approval.persisted');
      _appendOutbox(
        stream: 'shift.event',
        eventType: 'shift.difference-approved.v1',
        aggregateId: shiftId,
        aggregateVersion: expectedVersion,
        correlationId: commandId,
        payload: {
          'approvalId': approvalId,
          'shiftId': shiftId,
          'approverId': supervisor.supervisorId,
          'reasonCode': reasonCode,
          'theoreticalCashMinor': theoretical,
          'actualCashMinor': actualCashMinor,
          'differenceMinor': difference,
        },
        occurredAt: at,
      );
      _audit(
        'SHIFT_DIFFERENCE_APPROVED',
        'SHIFT',
        shiftId,
        commandId,
        'OPEN',
        'OPEN',
        difference,
        requestHash,
        at,
        actorId: supervisor.supervisorId,
        approverId: supervisor.supervisorId,
      );
      final result = ShiftCloseApproval(
        approvalId: approvalId,
        approverId: supervisor.supervisorId,
        reasonCode: reasonCode,
        reasonText: reasonText,
        actualCashMinor: actualCashMinor,
        differenceMinor: difference,
      );
      _saveIdempotency(
        'APPROVE_SHIFT_DIFFERENCE',
        commandId,
        idempotencyKey,
        requestHash,
        approvalId,
        result.toJson(),
        at,
      );
      return result;
    });
  }

  ShiftResult closeShift({
    required String commandId,
    required String idempotencyKey,
    required String shiftId,
    required int actualCashMinor,
    required int expectedVersion,
    required DateTime occurredAt,
    String? approvalId,
  }) {
    _requireCommand(commandId, idempotencyKey);
    MoneyRules.requireMinor(actualCashMinor, 'actualCashMinor');
    final requestHash = _hash([
      shiftId,
      actualCashMinor,
      expectedVersion,
      approvalId,
    ]);
    return localDatabase.transaction(() {
      final duplicate = _idempotent<ShiftResult>(
        'CLOSE_SHIFT',
        idempotencyKey,
        requestHash,
        ShiftResult.fromJson,
      );
      if (duplicate != null) return duplicate;
      final rows = _db.select(
        'SELECT * FROM local_shift WHERE tenant_id=? AND store_id=? AND terminal_id=? AND shift_id=?',
        [_binding.tenantId, _binding.storeId, _binding.terminalId, shiftId],
      );
      if (rows.length != 1 ||
          rows.single['status'] != 'OPEN' ||
          rows.single['cashier_id'] != _binding.cashierId ||
          rows.single['record_version'] != expectedVersion) {
        throw const PosDomainException(
          'SHIFT_STATE_CONFLICT',
          'shift close state or version conflict',
        );
      }
      final ledger = _cashLedgerTotal(shiftId);
      final theoretical = (rows.single['opening_cash_minor']! as int) + ledger;
      final difference = actualCashMinor - theoretical;
      Row? approval;
      if (difference.abs() > shiftPolicy.cashDifferenceApprovalMinor) {
        if (approvalId == null) {
          throw const PosDomainException(
            'SHIFT_DIFFERENCE_APPROVAL_REQUIRED',
            'independent approval is required',
          );
        }
        final approvals = _db.select(
          'SELECT * FROM local_shift_approval WHERE tenant_id=? AND shift_id=? AND approval_id=? AND status=\'APPROVED\' AND theoretical_cash_minor=? AND actual_cash_minor=? AND difference_minor=? AND expected_shift_version=?',
          [
            _binding.tenantId,
            shiftId,
            approvalId,
            theoretical,
            actualCashMinor,
            difference,
            expectedVersion,
          ],
        );
        if (approvals.length != 1 ||
            approvals.single['approver_id'] == _binding.cashierId) {
          throw const PosDomainException(
            'SHIFT_DIFFERENCE_APPROVAL_REQUIRED',
            'persisted independent approval does not match this count',
          );
        }
        approval = approvals.single;
      } else if (approvalId != null) {
        throw const PosDomainException(
          'SHIFT_APPROVAL_NOT_REQUIRED',
          'an approval cannot be attached within the threshold',
        );
      }
      final at = occurredAt.toUtc().toIso8601String();
      _db.execute(
        'UPDATE local_shift SET status=\'CLOSED\',theoretical_cash_minor=?,actual_cash_minor=?,difference_minor=?,approval_id=?,closed_at=?,record_version=record_version+1 WHERE tenant_id=? AND shift_id=? AND status=\'OPEN\' AND record_version=?',
        [
          theoretical,
          actualCashMinor,
          difference,
          approvalId,
          at,
          _binding.tenantId,
          shiftId,
          expectedVersion,
        ],
      );
      if (_db.updatedRows != 1) {
        throw const PosDomainException(
          'SHIFT_STATE_CONFLICT',
          'concurrent close conflict',
        );
      }
      localDatabase.checkpoint('shift.closed');
      _appendOutbox(
        stream: 'shift.event',
        eventType: 'shift.closed.v1',
        aggregateId: shiftId,
        aggregateVersion: expectedVersion + 1,
        correlationId: commandId,
        payload: {
          'shiftId': shiftId,
          'businessDate': rows.single['business_date'],
          'currency': 'CNY',
          'theoreticalCashMinor': theoretical,
          'actualCashMinor': actualCashMinor,
          'differenceMinor': difference,
          'approvalId': approvalId,
        },
        occurredAt: at,
      );
      _audit(
        'SHIFT_CLOSED',
        'SHIFT',
        shiftId,
        commandId,
        'OPEN',
        'CLOSED',
        difference,
        requestHash,
        at,
        approverId: approval?['approver_id'] as String?,
      );
      final result = ShiftResult(
        shiftId: shiftId,
        status: 'CLOSED',
        businessDate: rows.single['business_date']! as String,
        theoreticalCashMinor: theoretical,
        actualCashMinor: actualCashMinor,
        differenceMinor: difference,
        recordVersion: expectedVersion + 1,
      );
      _saveIdempotency(
        'CLOSE_SHIFT',
        commandId,
        idempotencyKey,
        requestHash,
        shiftId,
        result.toJson(),
        at,
      );
      return result;
    });
  }

  void _insertDraftOrder(Basket basket, String shiftId, DateTime occurredAt) {
    final shift = _requireOpenShift(shiftId);
    final gross = basket.grossAmountMinor;
    _db.execute(
      'INSERT INTO local_order(order_id,tenant_id,local_order_no,store_id,terminal_id,shift_id,cashier_id,business_date,store_timezone,status,draft_disposition,payment_status,currency,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,received_amount_minor,catalog_version,price_version,industry_template_version,occurred_at,record_version) VALUES(?,?,?,?,?,?,?,?,?,\'DRAFT\',\'ACTIVE\',\'UNPAID\',\'CNY\',?,0,0,?,0,1,1,\'DRAFT\',?,1)',
      [
        basket.orderId,
        _binding.tenantId,
        basket.localOrderNo,
        _binding.storeId,
        _binding.terminalId,
        shiftId,
        _binding.cashierId,
        shift['business_date'],
        _binding.storeTimezone,
        gross,
        gross,
        occurredAt.toUtc().toIso8601String(),
      ],
    );
    _insertLines(basket);
  }

  void _verifyPromotionSettlement(PromotedCashSaleCommand command) {
    final quoteRows = _db.select(
      'SELECT * FROM local_promotion_quote WHERE tenant_id=? AND store_id=? AND terminal_id=? AND quote_id=?',
      [
        _binding.tenantId,
        _binding.storeId,
        _binding.terminalId,
        command.quoteId,
      ],
    );
    if (quoteRows.length != 1) {
      throw const PosDomainException(
        'PROMOTION_QUOTE_NOT_FOUND',
        'quote is outside the trusted device context',
      );
    }
    final quote = quoteRows.single;
    final quoteDiscount = quote['discount_amount_minor']! as int;
    if (!const {'CALCULATED', 'FROZEN'}.contains(quote['status']) ||
        quote['package_version'] != command.packageVersion ||
        quote['result_sha256'] != command.quoteFingerprint ||
        quote['gross_amount_minor'] != command.grossAmountMinor) {
      throw const PosDomainException(
        'PROMOTION_QUOTE_MISMATCH',
        'quote identity, package, fingerprint or gross amount differs',
      );
    }
    final package = _db.select(
      'SELECT 1 FROM local_promotion_package_slot WHERE tenant_id=? AND store_id=? AND package_version=? AND state IN (\'ACTIVE\',\'RETIRED\')',
      [_binding.tenantId, _binding.storeId, command.packageVersion],
    );
    if (package.length != 1) {
      throw const PosDomainException(
        'PROMOTION_PACKAGE_UNAVAILABLE',
        'the quoted package is not retained for settlement',
      );
    }
    final quoteLines = _db.select(
      'SELECT source_line_id,line_no,sku_id,quantity_decimal,unit_price_minor,gross_amount_minor,discount_amount_minor,payable_amount_minor FROM local_promotion_quote_line WHERE tenant_id=? AND quote_id=? ORDER BY line_no',
      [_binding.tenantId, command.quoteId],
    );
    if (quoteLines.length != command.lines.length) {
      throw const PosDomainException(
        'PROMOTION_QUOTE_MISMATCH',
        'quote line count differs from frozen basket',
      );
    }
    var quotedLineDiscount = 0;
    for (var index = 0; index < command.lines.length; index++) {
      final actual = command.lines[index];
      final quoted = quoteLines[index];
      if (quoted['source_line_id'] != actual.basketLine.lineId ||
          quoted['line_no'] != actual.basketLine.lineNo ||
          quoted['sku_id'] != actual.basketLine.quote.skuId ||
          quoted['quantity_decimal'] != actual.basketLine.quantity.canonical ||
          quoted['unit_price_minor'] !=
              actual.basketLine.quote.unitPriceMinor ||
          quoted['gross_amount_minor'] != actual.basketLine.grossAmountMinor ||
          actual.discountAmountMinor <
              (quoted['discount_amount_minor']! as int)) {
        throw const PosDomainException(
          'PROMOTION_QUOTE_MISMATCH',
          'quote line differs from settlement line',
        );
      }
      quotedLineDiscount += quoted['discount_amount_minor']! as int;
    }
    if (quotedLineDiscount != quoteDiscount ||
        command.discountAmountMinor < quoteDiscount) {
      throw const PosDomainException(
        'PROMOTION_AMOUNT_MISMATCH',
        'quoted or settled discount is not conserved',
      );
    }
    if (command.manualEventRefs.toSet().length !=
        command.manualEventRefs.length) {
      throw const PosDomainException(
        'PROMOTION_MANUAL_CHAIN_INVALID',
        'manual event references contain duplicates',
      );
    }
    var fingerprint = command.quoteFingerprint;
    var manualDiscount = 0;
    for (final eventId in command.manualEventRefs) {
      final events = _db.select(
        'SELECT * FROM local_promotion_manual_event WHERE tenant_id=? AND store_id=? AND terminal_id=? AND quote_id=? AND manual_event_id=? AND state=\'APPLIED\'',
        [
          _binding.tenantId,
          _binding.storeId,
          _binding.terminalId,
          command.quoteId,
          eventId,
        ],
      );
      if (events.length != 1 ||
          events.single['package_version'] != command.packageVersion ||
          events.single['before_fingerprint'] != fingerprint) {
        throw const PosDomainException(
          'PROMOTION_MANUAL_CHAIN_INVALID',
          'manual approval chain is missing, stale or out of order',
        );
      }
      fingerprint = events.single['preview_fingerprint']! as String;
      manualDiscount += events.single['incremental_discount_minor']! as int;
    }
    if (fingerprint != command.settlementFingerprint ||
        quoteDiscount + manualDiscount != command.discountAmountMinor ||
        command.surchargeAmountMinor != 0 ||
        command.grossAmountMinor - command.discountAmountMinor !=
            command.receivableAmountMinor) {
      throw const PosDomainException(
        'PROMOTION_AMOUNT_MISMATCH',
        'manual chain fingerprint or final amount differs',
      );
    }
  }

  void _insertPromotedCompletedOrder(
    PromotedCashSaleCommand command,
    String snapshotJson,
    String snapshotHash,
    String requestHash,
    String at,
  ) {
    _db.execute(
      'INSERT INTO local_order(order_id,tenant_id,local_order_no,store_id,terminal_id,shift_id,cashier_id,business_date,store_timezone,status,draft_disposition,payment_status,currency,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,received_amount_minor,catalog_version,price_version,industry_template_version,snapshot_schema_version,snapshot_json,snapshot_sha256,idempotency_key,request_sha256,occurred_at,record_version) VALUES(?,?,?,?,?,?,?,?,?,\'COMPLETED\',\'ACTIVE\',\'PAID\',\'CNY\',?,?,?,?,?,?,?,?,2,?,?,?,?,?,4)',
      [
        command.basket.orderId,
        _binding.tenantId,
        command.basket.localOrderNo,
        _binding.storeId,
        _binding.terminalId,
        command.shiftId,
        _binding.cashierId,
        command.businessDate,
        _binding.storeTimezone,
        command.grossAmountMinor,
        command.discountAmountMinor,
        command.surchargeAmountMinor,
        command.receivableAmountMinor,
        command.receivableAmountMinor,
        command.catalogVersion,
        command.priceVersion,
        command.industryTemplateVersion,
        snapshotJson,
        snapshotHash,
        command.idempotencyKey,
        requestHash,
        at,
      ],
    );
  }

  void _insertPromotedLines(PromotedCashSaleCommand command) {
    for (final promoted in command.lines) {
      final line = promoted.basketLine;
      _db.execute(
        'INSERT INTO local_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,barcode_value,product_name_snapshot,unit_id,unit_code,quantity_decimal,unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor,price_source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
        [
          line.lineId,
          _binding.tenantId,
          command.basket.orderId,
          line.lineNo,
          line.quote.skuId,
          line.quote.skuCode,
          line.quote.barcode,
          line.quote.productName,
          line.quote.unitId,
          line.quote.unitCode,
          line.quantity.canonical,
          line.quote.unitPriceMinor,
          line.grossAmountMinor,
          promoted.discountAmountMinor,
          promoted.surchargeAmountMinor,
          promoted.receivableAmountMinor,
          line.quote.priceSource,
        ],
      );
    }
  }

  void _applyPromotedLineAmounts(PromotedCashSaleCommand command) {
    for (final promoted in command.lines) {
      _db.execute(
        'UPDATE local_order_line SET discount_amount_minor=?,surcharge_amount_minor=?,payable_amount_minor=? WHERE tenant_id=? AND order_id=? AND line_id=? AND gross_amount_minor=?',
        [
          promoted.discountAmountMinor,
          promoted.surchargeAmountMinor,
          promoted.receivableAmountMinor,
          _binding.tenantId,
          command.basket.orderId,
          promoted.basketLine.lineId,
          promoted.basketLine.grossAmountMinor,
        ],
      );
      if (_db.updatedRows != 1) {
        throw const PosDomainException(
          'ORDER_AMOUNT_CHANGED',
          'draft line cannot be frozen with promoted amount',
        );
      }
    }
  }

  void _insertPromotionSnapshot(
    PromotedCashSaleCommand command,
    String promotionHash,
    String at,
  ) {
    _db.execute(
      'INSERT INTO local_promotion_transaction_snapshot(snapshot_id,tenant_id,order_id,quote_id,store_id,terminal_id,business_date,currency,quote_fingerprint,snapshot_sha256,gross_amount_minor,discount_amount_minor,payable_amount_minor,actor_user_id,correlation_id,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
      [
        command.promotionSnapshotId,
        _binding.tenantId,
        command.basket.orderId,
        command.quoteId,
        _binding.storeId,
        _binding.terminalId,
        command.businessDate,
        'CNY',
        command.settlementFingerprint,
        promotionHash,
        command.grossAmountMinor,
        command.discountAmountMinor,
        command.receivableAmountMinor,
        _binding.cashierId,
        command.commandId,
        at,
      ],
    );
    for (final promoted in command.lines) {
      final sourceJson = PromotedOrderSnapshotCodec.canonicalJson(
        promoted.sourceAllocations,
      );
      final sourceHash = sha256.convert(utf8.encode(sourceJson)).toString();
      _db.execute(
        'INSERT INTO local_promotion_transaction_allocation(allocation_id,tenant_id,snapshot_id,line_id,line_no,sku_id,quantity_decimal,gross_amount_minor,discount_amount_minor,payable_amount_minor,source_allocations_json,source_allocations_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
        [
          ulids.next(),
          _binding.tenantId,
          command.promotionSnapshotId,
          promoted.basketLine.lineId,
          promoted.basketLine.lineNo,
          promoted.basketLine.quote.skuId,
          promoted.basketLine.quantity.canonical,
          promoted.basketLine.grossAmountMinor,
          promoted.discountAmountMinor,
          promoted.receivableAmountMinor,
          sourceJson,
          sourceHash,
        ],
      );
    }
  }

  void _insertPromotedStateHistory(
    PromotedCashSaleCommand command, {
    required int startingVersion,
    required bool includeDraft,
    required String at,
  }) {
    final states = includeDraft
        ? <(String?, String)>[
            (null, 'DRAFT'),
            ('DRAFT', 'PENDING_PAYMENT'),
            ('PENDING_PAYMENT', 'CONFIRMED'),
            ('CONFIRMED', 'COMPLETED'),
          ]
        : <(String?, String)>[
            ('DRAFT', 'PENDING_PAYMENT'),
            ('PENDING_PAYMENT', 'CONFIRMED'),
            ('CONFIRMED', 'COMPLETED'),
          ];
    var version = startingVersion;
    for (final state in states) {
      _db.execute(
        'INSERT INTO local_order_state_history(history_id,tenant_id,order_id,command_id,from_status,to_status,aggregate_version,actor_id,reason_code,occurred_at) VALUES(?,?,?,?,?,?,?,?,\'PROMOTED_CASH_SALE\',?)',
        [
          ulids.next(),
          _binding.tenantId,
          command.basket.orderId,
          command.commandId,
          state.$1,
          state.$2,
          version++,
          _binding.cashierId,
          at,
        ],
      );
    }
  }

  Map<String, Object?> _promotionSnapshot(PromotedCashSaleCommand command) => {
    'snapshotId': command.promotionSnapshotId,
    'orderId': command.basket.orderId,
    'quoteId': command.quoteId,
    'storeId': int.parse(_binding.storeId),
    'terminalId': _binding.terminalId,
    'currency': 'CNY',
    'quoteFingerprint': command.quoteFingerprint,
    'grossAmountMinor': command.grossAmountMinor,
    'discountAmountMinor': command.discountAmountMinor,
    'payableAmountMinor':
        command.grossAmountMinor - command.discountAmountMinor,
    'lines': command.lines
        .map(
          (line) => {
            'lineId': line.basketLine.lineId,
            'lineNo': line.basketLine.lineNo,
            'skuId': int.parse(line.basketLine.quote.skuId),
            'quantity': line.basketLine.quantity.canonical,
            'grossAmountMinor': line.basketLine.grossAmountMinor,
            'discountAmountMinor': line.discountAmountMinor,
            'payableAmountMinor':
                line.basketLine.grossAmountMinor - line.discountAmountMinor,
            'sourceAllocationsSha256': PromotedOrderSnapshotCodec.sha256Hex(
              line.sourceAllocations,
            ),
          },
        )
        .toList(),
  };

  void _insertCompletedOrder(
    CashSaleCommand command,
    String snapshotJson,
    String snapshotHash,
    String requestHash,
    String at,
  ) {
    final gross = command.basket.grossAmountMinor;
    _db.execute(
      'INSERT INTO local_order(order_id,tenant_id,local_order_no,store_id,terminal_id,shift_id,cashier_id,business_date,store_timezone,status,draft_disposition,payment_status,currency,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,received_amount_minor,catalog_version,price_version,industry_template_version,snapshot_schema_version,snapshot_json,snapshot_sha256,idempotency_key,request_sha256,occurred_at,record_version) VALUES(?,?,?,?,?,?,?,?,?,\'COMPLETED\',\'ACTIVE\',\'PAID\',\'CNY\',?,0,0,?,?,?, ?,?,1,?,?,?,?,?,4)',
      [
        command.basket.orderId,
        _binding.tenantId,
        command.basket.localOrderNo,
        _binding.storeId,
        _binding.terminalId,
        command.shiftId,
        _binding.cashierId,
        command.businessDate,
        _binding.storeTimezone,
        gross,
        gross,
        gross,
        command.catalogVersion,
        command.priceVersion,
        command.industryTemplateVersion,
        snapshotJson,
        snapshotHash,
        command.idempotencyKey,
        requestHash,
        at,
      ],
    );
  }

  void _insertLines(Basket basket) {
    for (final line in basket.lines) {
      _db.execute(
        'INSERT INTO local_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,barcode_value,product_name_snapshot,unit_id,unit_code,quantity_decimal,unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor,price_source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,?,?)',
        [
          line.lineId,
          _binding.tenantId,
          basket.orderId,
          line.lineNo,
          line.quote.skuId,
          line.quote.skuCode,
          line.quote.barcode,
          line.quote.productName,
          line.quote.unitId,
          line.quote.unitCode,
          line.quantity.canonical,
          line.quote.unitPriceMinor,
          line.grossAmountMinor,
          line.grossAmountMinor,
          line.quote.priceSource,
        ],
      );
    }
  }

  void _verifyPersistedLines(Basket basket) {
    final persisted = _db.select(
      'SELECT line_no,sku_id,unit_id,quantity_decimal,unit_price_minor,gross_amount_minor,price_source FROM local_order_line WHERE tenant_id=? AND order_id=? ORDER BY line_no',
      [_binding.tenantId, basket.orderId],
    );
    final lines = [...basket.lines]
      ..sort((a, b) => a.lineNo.compareTo(b.lineNo));
    if (persisted.length != lines.length) {
      throw const PosDomainException(
        'ORDER_AMOUNT_CHANGED',
        'persisted line count differs',
      );
    }
    for (var index = 0; index < lines.length; index++) {
      final row = persisted[index];
      final line = lines[index];
      if (row['line_no'] != line.lineNo ||
          row['sku_id'] != line.quote.skuId ||
          row['unit_id'] != line.quote.unitId ||
          row['quantity_decimal'] != line.quantity.canonical ||
          row['unit_price_minor'] != line.quote.unitPriceMinor ||
          row['gross_amount_minor'] != line.grossAmountMinor ||
          row['price_source'] != line.quote.priceSource) {
        throw const PosDomainException(
          'ORDER_AMOUNT_CHANGED',
          'persisted lines differ from resumed basket',
        );
      }
    }
  }

  void _insertStateHistory(
    CashSaleCommand command, {
    required int startingVersion,
    required bool includeDraft,
    required String at,
  }) {
    final states = includeDraft
        ? <(String?, String)>[
            (null, 'DRAFT'),
            ('DRAFT', 'PENDING_PAYMENT'),
            ('PENDING_PAYMENT', 'CONFIRMED'),
            ('CONFIRMED', 'COMPLETED'),
          ]
        : <(String?, String)>[
            ('DRAFT', 'PENDING_PAYMENT'),
            ('PENDING_PAYMENT', 'CONFIRMED'),
            ('CONFIRMED', 'COMPLETED'),
          ];
    var version = startingVersion;
    for (final state in states) {
      _db.execute(
        'INSERT INTO local_order_state_history(history_id,tenant_id,order_id,command_id,from_status,to_status,aggregate_version,actor_id,reason_code,occurred_at) VALUES(?,?,?,?,?,?,?,?,\'CASH_SALE\',?)',
        [
          ulids.next(),
          _binding.tenantId,
          command.basket.orderId,
          command.commandId,
          state.$1,
          state.$2,
          version++,
          _binding.cashierId,
          at,
        ],
      );
    }
  }

  Row _requireOpenShift(String shiftId, {String? businessDate}) {
    final rows = _db.select(
      'SELECT * FROM local_shift WHERE tenant_id=? AND store_id=? AND terminal_id=? AND cashier_id=? AND shift_id=? AND status=\'OPEN\'',
      [
        _binding.tenantId,
        _binding.storeId,
        _binding.terminalId,
        _binding.cashierId,
        shiftId,
      ],
    );
    if (rows.length != 1 ||
        (businessDate != null &&
            rows.single['business_date'] != businessDate)) {
      throw const PosDomainException(
        'SHIFT_NOT_OPEN',
        'trusted binding has no matching open shift',
      );
    }
    return rows.single;
  }

  int _cashLedgerTotal(String shiftId) {
    final sale =
        _db.select(
              'SELECT COALESCE(SUM(signed_amount_minor),0) total FROM local_cash_ledger WHERE tenant_id=? AND shift_id=?',
              [_binding.tenantId, shiftId],
            ).single['total']!
            as int;
    final nonSale =
        _db.select(
              'SELECT COALESCE(SUM(signed_amount_minor),0) total FROM local_shift_cash_movement WHERE tenant_id=? AND shift_id=?',
              [_binding.tenantId, shiftId],
            ).single['total']!
            as int;
    return sale + nonSale;
  }

  bool _matchesDraftContext(
    Row row,
    String shiftId,
    String localOrderNo, {
    String? businessDate,
  }) =>
      row['status'] == 'DRAFT' &&
      row['tenant_id'] == _binding.tenantId &&
      row['store_id'] == _binding.storeId &&
      row['terminal_id'] == _binding.terminalId &&
      row['cashier_id'] == _binding.cashierId &&
      row['shift_id'] == shiftId &&
      row['local_order_no'] == localOrderNo &&
      (businessDate == null || row['business_date'] == businessDate) &&
      row['store_timezone'] == _binding.storeTimezone;

  Map<String, Object?> _snapshot(CashSaleCommand command) => {
    'schemaVersion': 1,
    'orderId': command.basket.orderId,
    'storeId': _binding.storeId,
    'terminalId': _binding.terminalId,
    'shiftId': command.shiftId,
    'cashierId': _binding.cashierId,
    'businessDate': command.businessDate,
    'storeTimezone': _binding.storeTimezone,
    'currency': 'CNY',
    'grossAmountMinor': command.basket.grossAmountMinor,
    'discountAmountMinor': 0,
    'surchargeAmountMinor': 0,
    'receivableAmountMinor': command.basket.grossAmountMinor,
    'catalogVersion': command.catalogVersion,
    'priceVersion': command.priceVersion,
    'industryTemplateVersion': command.industryTemplateVersion,
    'lines':
        ([...command.basket.lines]
              ..sort((a, b) => a.lineNo.compareTo(b.lineNo)))
            .map((line) => line.toSnapshot())
            .toList(),
  };

  /// 从已落库的不可变订单事实生成语义收据，并创建唯一原始打印请求。
  ({String documentId, String contentSha256, String payloadJson})
  _freezeReceiptDocument({
    required String orderId,
    required String printJobId,
    required String templateVersion,
    required String at,
  }) {
    final orders = _db.select(
      '''SELECT local_order_no,store_id,terminal_id,shift_id,cashier_id,business_date,currency,
         gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor
         FROM local_order WHERE tenant_id=? AND order_id=? AND status='COMPLETED' ''',
      [_binding.tenantId, orderId],
    );
    if (orders.length != 1) {
      throw const PosDomainException(
        'RECEIPT_SOURCE_INVALID',
        'completed order source is missing',
      );
    }
    final order = orders.single;
    final lines = _db.select(
      '''SELECT line_no,sku_code,barcode_value,product_name_snapshot,unit_code,quantity_decimal,
         unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor
         FROM local_order_line WHERE tenant_id=? AND order_id=? ORDER BY line_no''',
      [_binding.tenantId, orderId],
    );
    if (lines.isEmpty || lines.length > 500) {
      throw const PosDomainException(
        'RECEIPT_SOURCE_INVALID',
        'receipt must contain between one and 500 order lines',
      );
    }
    final payload = <String, Object?>{
      'schemaVersion': 1,
      'documentType': 'SALE_RECEIPT',
      'orderId': orderId,
      'localOrderNo': order['local_order_no'],
      'storeId': order['store_id'],
      'terminalId': order['terminal_id'],
      'shiftId': order['shift_id'],
      'cashierId': order['cashier_id'],
      'cashierName': _binding.cashierName,
      'businessDate': order['business_date'],
      'currency': order['currency'],
      'templateVersion': templateVersion,
      'grossAmountMinor': order['gross_amount_minor'],
      'discountAmountMinor': order['discount_amount_minor'],
      'surchargeAmountMinor': order['surcharge_amount_minor'],
      'receivableAmountMinor': order['receivable_amount_minor'],
      'lines': lines
          .map(
            (line) => <String, Object?>{
              'lineNo': line['line_no'],
              'skuCode': line['sku_code'],
              'barcode': line['barcode_value'],
              'name': line['product_name_snapshot'],
              'unitCode': line['unit_code'],
              'quantity': line['quantity_decimal'],
              'unitPriceMinor': line['unit_price_minor'],
              'grossAmountMinor': line['gross_amount_minor'],
              'discountAmountMinor': line['discount_amount_minor'],
              'surchargeAmountMinor': line['surcharge_amount_minor'],
              'payableAmountMinor': line['payable_amount_minor'],
            },
          )
          .toList(growable: false),
    };
    final payloadJson = jsonEncode(payload);
    if (utf8.encode(payloadJson).length > 1024 * 1024) {
      throw const PosDomainException(
        'RECEIPT_PAYLOAD_TOO_LARGE',
        'receipt semantic payload exceeds the one MiB limit',
      );
    }
    final contentSha256 = sha256.convert(utf8.encode(payloadJson)).toString();
    final documentId = ulids.next();
    _db.execute(
      '''INSERT INTO local_receipt_document(document_id,tenant_id,order_id,document_type,
         template_version,template_schema_version,semantic_payload_json,content_sha256,frozen_at)
         VALUES(?,?,?,'SALE_RECEIPT',?,1,?,?,?)''',
      [
        documentId,
        _binding.tenantId,
        orderId,
        templateVersion,
        payloadJson,
        contentSha256,
        at,
      ],
    );
    final printRequestId = ulids.next();
    final idempotencyKey = 'print-original:$orderId';
    final requestSha256 = _hash([
      printRequestId,
      printJobId,
      orderId,
      documentId,
      contentSha256,
      'ORIGINAL',
    ]);
    _db.execute(
      '''INSERT INTO local_print_request(print_request_id,tenant_id,print_job_id,order_id,document_id,
         request_kind,reprint_no,requested_by,requested_by_name,authorization_ref,reason_code,reason_text,
         idempotency_key,request_sha256,document_sha256,execution_status,adapter_evidence,requested_at)
         VALUES(?,?,?,?,?,'ORIGINAL',0,?,?,?,'ORDER_COMPLETED','成交后原始小票任务',?,?,?,
         'BLOCKED_EXTERNAL','BLOCKED_REAL_PRINTER',?)''',
      [
        printRequestId,
        _binding.tenantId,
        printJobId,
        orderId,
        documentId,
        _binding.cashierId,
        _binding.cashierName,
        'SYSTEM_ORDER_COMPLETION',
        idempotencyKey,
        requestSha256,
        contentSha256,
        at,
      ],
    );
    localDatabase.checkpoint('receipt.frozen');
    return (
      documentId: documentId,
      contentSha256: contentSha256,
      payloadJson: payloadJson,
    );
  }

  void _appendReceiptFrozenEvent({
    required ({String documentId, String contentSha256, String payloadJson})
    receipt,
    required String printJobId,
    required int aggregateVersion,
    required String correlationId,
    required String occurredAt,
  }) {
    final payload = (jsonDecode(receipt.payloadJson) as Map)
        .cast<String, Object?>();
    _appendOutbox(
      stream: 'order.command',
      eventType: 'receipt.document-frozen.v1',
      aggregateId: receipt.documentId,
      aggregateVersion: 1,
      correlationId: correlationId,
      payload: {
        'documentId': receipt.documentId,
        'printJobId': printJobId,
        'orderId': payload['orderId'],
        'storeId': _binding.storeId,
        'terminalId': _binding.terminalId,
        'cashierId': _binding.cashierId,
        'documentType': 'SALE_RECEIPT',
        'templateVersion': payload['templateVersion'],
        'templateSchemaVersion': 1,
        'contentSha256': receipt.contentSha256,
        'semanticPayload': payload,
        'orderAggregateVersion': aggregateVersion,
        'executionStatus': 'BLOCKED_EXTERNAL',
      },
      occurredAt: occurredAt,
    );
  }

  String _appendOutbox({
    required String stream,
    required String eventType,
    required String aggregateId,
    required int aggregateVersion,
    required String correlationId,
    required Map<String, Object?> payload,
    required String occurredAt,
  }) {
    final eventId = ulids.next();
    final payloadJson = jsonEncode(payload);
    final payloadHash = sha256.convert(utf8.encode(payloadJson)).toString();
    _db.execute(
      'INSERT INTO local_outbox(event_id,tenant_id,device_sequence,stream_code,event_type,aggregate_id,aggregate_version,correlation_id,payload_json,payload_sha256,status,attempt_count,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,\'PENDING\',0,?)',
      [
        eventId,
        _binding.tenantId,
        localDatabase.nextDeviceSequence(),
        stream,
        eventType,
        aggregateId,
        aggregateVersion,
        correlationId,
        payloadJson,
        payloadHash,
        occurredAt,
      ],
    );
    return eventId;
  }

  void _audit(
    String action,
    String aggregateType,
    String aggregateId,
    String commandId,
    String? beforeStatus,
    String afterStatus,
    int? amount,
    String requestHash,
    String at, {
    String? actorId,
    String? approverId,
  }) {
    _db.execute(
      'INSERT INTO local_audit_event(audit_id,tenant_id,action_code,aggregate_type,aggregate_id,actor_id,approver_id,command_id,trace_id,before_status,after_status,amount_minor,currency,request_sha256,reason_code,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
      [
        ulids.next(),
        _binding.tenantId,
        action,
        aggregateType,
        aggregateId,
        actorId ?? _binding.cashierId,
        approverId,
        commandId,
        commandId,
        beforeStatus,
        afterStatus,
        amount,
        amount == null ? null : 'CNY',
        requestHash,
        action,
        at,
      ],
    );
  }

  T? _idempotent<T>(
    String type,
    String key,
    String requestHash,
    T Function(Map<String, Object?> json, {bool duplicate}) parse,
  ) {
    final rows = _db.select(
      'SELECT request_sha256,result_json FROM local_idempotency WHERE tenant_id=? AND command_type=? AND idempotency_key=?',
      [_binding.tenantId, type, key],
    );
    if (rows.isEmpty) return null;
    if (rows.single['request_sha256'] != requestHash) {
      throw const PosDomainException(
        'IDEMPOTENCY_KEY_REUSED',
        'same key has a different request hash',
      );
    }
    return parse(
      (jsonDecode(rows.single['result_json']! as String) as Map)
          .cast<String, Object?>(),
      duplicate: true,
    );
  }

  void _saveIdempotency(
    String type,
    String commandId,
    String key,
    String requestHash,
    String aggregateId,
    Map<String, Object?> result,
    String at,
  ) {
    _db.execute(
      'INSERT INTO local_idempotency(idempotency_id,tenant_id,command_type,command_id,idempotency_key,request_sha256,aggregate_id,result_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)',
      [
        ulids.next(),
        _binding.tenantId,
        type,
        commandId,
        key,
        requestHash,
        aggregateId,
        jsonEncode(result),
        at,
      ],
    );
  }

  void _requireCommand(String commandId, String idempotencyKey) {
    if (!UlidGenerator.isCanonical(commandId) ||
        !RegExp(r'^[A-Za-z0-9._:-]{16,128}$').hasMatch(idempotencyKey)) {
      throw const PosDomainException(
        'ORD-IDEM-001',
        'invalid command or idempotency identity',
      );
    }
  }

  bool _isCanonicalBusinessDate(String value) {
    final match = RegExp(r'^(\d{4})-(\d{2})-(\d{2})$').firstMatch(value);
    if (match == null) return false;
    final year = int.parse(match.group(1)!);
    final month = int.parse(match.group(2)!);
    final day = int.parse(match.group(3)!);
    final parsed = DateTime.utc(year, month, day);
    return parsed.year == year && parsed.month == month && parsed.day == day;
  }

  String _hash(List<Object?> values) {
    final canonical = values.map((value) {
      final text = '$value';
      return '${text.length}:$text;';
    }).join();
    return sha256.convert(utf8.encode(canonical)).toString();
  }
}
