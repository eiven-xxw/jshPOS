import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../catalog/infrastructure/catalog_package_installer.dart';
import '../../checkout/application/checkout_local_service.dart';
import '../../checkout/domain/checkout_models.dart';
import '../../checkout/domain/exact_quantity.dart';
import '../../checkout/domain/ulid_generator.dart';
import '../../promotion/application/local_manual_adjustment_service.dart';
import '../../promotion/application/local_promotion_quote_service.dart';
import '../../promotion/domain/manual_adjustment_engine.dart';
import '../../promotion/domain/promotion_engine.dart';
import '../../synchronization/application/sync_coordinator.dart';
import '../application/pos_sale_application_service.dart';
import '../domain/pos_sale_models.dart';

/// 正式 POS 本地销售组合根：页面只提交意图，商品、促销、Checkout 与 Sync 各守其主权。
final class LocalPosSaleApplicationService
    implements PosSaleApplicationService {
  LocalPosSaleApplicationService({
    required this.database,
    required this.catalog,
    required this.promotions,
    required this.manualAdjustments,
    required this.checkout,
    required this.ulids,
    required this.industryTemplateVersion,
    this.syncCoordinator,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final PosLocalDatabase database;
  final CatalogPackageInstaller catalog;
  final LocalPromotionQuoteService promotions;
  final LocalManualAdjustmentService manualAdjustments;
  final CheckoutLocalService checkout;
  final PosSyncCoordinator? syncCoordinator;
  final UlidGenerator ulids;
  final String industryTemplateVersion;
  final DateTime Function() _now;

  Basket? _basket;
  LocalPromotionQuoteResult? _baseQuote;
  PromotionQuote? _currentQuote;
  String? _settlementFingerprint;
  Map<String, Map<String, int>> _sources = const {};
  final List<String> _manualEvents = [];
  final Map<String, CatalogResolvedPrice> _products = {};
  String? _manualAuthorizationRef;

  @override
  Future<PosSaleWorkspace> loadWorkspace() async {
    _requireOpenShift();
    _basket ??= _newBasket();
    return _workspace();
  }

  @override
  Future<PosSaleWorkspace> scanBarcode(String barcode) async {
    final product = catalog.resolveBarcode(barcode, at: _now().toUtc());
    return _addResolved(product);
  }

  @override
  Future<List<PosProductView>> searchProducts(String keyword) async {
    final results = catalog.search(keyword, at: _now().toUtc());
    for (final result in results) {
      _products[result.product.skuId] = result;
    }
    return results.map(_productView).toList(growable: false);
  }

  @override
  Future<PosSaleWorkspace> addProduct(String productRef) async {
    final resolved =
        _products[productRef] ??
        catalog.resolveSku(productRef, at: _now().toUtc());
    return _addResolved(resolved);
  }

  Future<PosSaleWorkspace> _addResolved(CatalogResolvedPrice resolved) async {
    _requireOpenShift();
    final basket = _basket ??= _newBasket();
    _products[resolved.product.skuId] = resolved;
    final existing = basket.lines
        .where((line) => line.quote.skuId == resolved.product.skuId)
        .toList();
    if (existing.isEmpty) {
      basket.add(
        BasketLine(
          lineId: ulids.next(),
          lineNo: basket.lines.length + 1,
          quote: resolved.toCheckoutQuote(),
          quantity: '1',
        ),
      );
    } else {
      _replaceQuantity(existing.single.lineId, '+1');
    }
    await _requote();
    return _workspace();
  }

  @override
  Future<PosSaleWorkspace> changeQuantity(
    String lineRef,
    String quantity,
  ) async {
    _replaceQuantity(lineRef, quantity);
    await _requote();
    return _workspace();
  }

  void _replaceQuantity(String lineRef, String requested) {
    final basket = _requireBasket();
    final current = basket.lines
        .where((line) => line.lineId == lineRef)
        .toList();
    if (current.length != 1) {
      throw const PosSaleFailure('SALE_LINE_NOT_FOUND', '购物篮行不存在。');
    }
    final target = requested == '+1'
        ? _addWhole(current.single.quantity.canonical, 1)
        : requested == '-1'
        ? _addWhole(current.single.quantity.canonical, -1)
        : ExactQuantity.parse(requested).canonical;
    final rebuilt = <BasketLine>[];
    var lineNo = 0;
    for (final line in basket.lines) {
      if (line.lineId == lineRef && target == '0') continue;
      rebuilt.add(
        BasketLine(
          lineId: line.lineId,
          lineNo: ++lineNo,
          quote: line.quote,
          quantity: line.lineId == lineRef ? target : line.quantity.canonical,
        ),
      );
    }
    _basket = Basket(
      orderId: basket.orderId,
      localOrderNo: basket.localOrderNo,
      lines: rebuilt,
    );
    _clearQuote();
  }

  @override
  Future<PosSaleWorkspace> refreshPromotionQuote() async {
    await _requote();
    return _workspace();
  }

  Future<void> _requote() async {
    final basket = _requireBasket();
    if (basket.lines.isEmpty) {
      _clearQuote();
      return;
    }
    final quote = await promotions.quote(
      pricingRequestId: ulids.next(),
      businessTime: _now().toUtc(),
      channel: 'POS',
      lines: _promotionLines(basket),
    );
    _baseQuote = quote;
    _currentQuote = quote.quote;
    _settlementFingerprint = quote.quoteFingerprint;
    _sources = {
      for (final entry in quote.sourceAllocationsByLine.entries)
        entry.key: Map<String, int>.from(entry.value),
    };
    _manualEvents.clear();
    _manualAuthorizationRef = null;
  }

  @override
  Future<PosSaleWorkspace> applyManualAdjustment({
    required String actionCode,
    required String value,
    String? lineRef,
    String? supervisorCredential,
  }) async {
    final base = _baseQuote;
    final current = _currentQuote;
    final fingerprint = _settlementFingerprint;
    if (base == null || current == null || fingerprint == null) {
      throw const PosSaleFailure('PROMOTION_QUOTE_REQUIRED', '请先完成促销报价。');
    }
    final action = switch (actionCode) {
      'LINE_FIXED_PRICE' => ManualActionType.lineFixedPrice,
      'ORDER_AMOUNT_OFF' => ManualActionType.orderAmountOff,
      'ORDER_PERCENT_OFF' => ManualActionType.orderPercentOff,
      'ROUNDING' => ManualActionType.rounding,
      _ => throw const PosSaleFailure('MANUAL_ACTION_INVALID', '人工优惠类型无效。'),
    };
    try {
      final result = await manualAdjustments.apply(
        baseQuote: base,
        currentQuote: current,
        beforeFingerprint: fingerprint,
        currentSources: _sources,
        lines: _promotionLines(_requireBasket()),
        actionType: action,
        amountOrRate: value.trim(),
        paymentMethod: ManualPaymentMethod.cash,
        businessDate: _requireOpenShift()['business_date']! as String,
        lineId: lineRef,
        supervisorCredential: supervisorCredential,
      );
      _currentQuote = result.quote;
      _settlementFingerprint = result.previewFingerprint;
      _sources = result.sourceAllocationsByLine;
      _manualEvents.add(result.manualEventId);
      _manualAuthorizationRef = result.manualEventId;
      return _workspace();
    } on PosSaleFailure {
      rethrow;
    } on Object {
      throw const PosSaleFailure(
        'MANUAL_ADJUSTMENT_REJECTED',
        '人工优惠未通过策略或授权校验。',
      );
    }
  }

  @override
  Future<PosSaleWorkspace> holdCurrentSale() async {
    final basket = _requireBasket();
    final shift = _requireOpenShift();
    checkout.suspendBasket(
      commandId: ulids.next(),
      idempotencyKey: 'hold:${basket.orderId}',
      basket: basket,
      shiftId: shift['shift_id']! as String,
      occurredAt: _now().toUtc(),
    );
    _basket = _newBasket();
    _clearQuote();
    return _workspace();
  }

  @override
  Future<PosSaleWorkspace> resumeHeldSale(String saleRef) async {
    final shift = _requireOpenShift();
    final basket = checkout.resumeBasket(
      commandId: ulids.next(),
      idempotencyKey: 'resume:$saleRef',
      orderId: saleRef,
      shiftId: shift['shift_id']! as String,
      occurredAt: _now().toUtc(),
    );
    for (final line in basket.lines) {
      final current = catalog.resolveSku(line.quote.skuId, at: _now().toUtc());
      if (current.amountMinor != line.quote.unitPriceMinor ||
          current.product.unitId != line.quote.unitId) {
        throw const PosSaleFailure('HELD_PRICE_CHANGED', '挂单价格已变化，请撤销后重新下单。');
      }
      _products[current.product.skuId] = current;
    }
    _basket = basket;
    await _requote();
    return _workspace();
  }

  @override
  Future<PosCashSettlementView> settleCash({
    required String tenderedAmount,
    required String idempotencyKey,
  }) async {
    final basket = _requireBasket();
    final shift = _requireOpenShift();
    final base = _baseQuote;
    final current = _currentQuote;
    final fingerprint = _settlementFingerprint;
    if (basket.lines.isEmpty ||
        base == null ||
        current == null ||
        fingerprint == null) {
      throw const PosSaleFailure('PROMOTION_QUOTE_REQUIRED', '购物篮尚未完成报价。');
    }
    final versions = <CatalogResolvedPrice>[];
    for (final line in basket.lines) {
      final resolved = catalog.resolveSku(line.quote.skuId, at: _now().toUtc());
      if (resolved.amountMinor != line.quote.unitPriceMinor ||
          resolved.product.unitId != line.quote.unitId) {
        throw const PosSaleFailure('PRICE_CHANGED', '商品价格已变化，请重新加购。');
      }
      versions.add(resolved);
    }
    final settlementLines = basket.lines
        .map((line) {
          final discount = current.lineDiscounts[line.lineId] ?? 0;
          return PromotedSettlementLine(
            basketLine: line,
            discountAmountMinor: discount,
            sourceAllocations: _sources[line.lineId] ?? const {},
          );
        })
        .toList(growable: false);
    final occurredAt = _now().toUtc();
    try {
      final result = checkout.completePromotedCashSale(
        PromotedCashSaleCommand(
          commandId: ulids.next(),
          idempotencyKey: idempotencyKey,
          basket: basket,
          shiftId: shift['shift_id']! as String,
          businessDate: shift['business_date']! as String,
          catalogVersion: versions
              .map((value) => value.catalogVersion)
              .reduce(_max),
          priceVersion: versions
              .map((value) => value.priceVersion)
              .reduce(_max),
          industryTemplateVersion: industryTemplateVersion,
          quoteId: base.quoteId,
          quoteFingerprint: base.quoteFingerprint,
          settlementFingerprint: fingerprint,
          packageVersion: base.packageVersion,
          promotionSnapshotId: ulids.next(),
          lines: settlementLines,
          manualEventRefs: _manualEvents,
          tenderedAmountMinor: _parseYuan(tenderedAmount),
          occurredAt: occurredAt,
        ),
      );
      final localOrderNo = basket.localOrderNo;
      _basket = null;
      _clearQuote();
      return PosCashSettlementView(
        orderRef: result.orderId,
        localOrderNo: localOrderNo,
        receivableAmountMinor: result.receivableAmountMinor,
        tenderedAmountMinor: result.tenderedAmountMinor,
        changeAmountMinor: result.changeAmountMinor,
        snapshotDigest: result.orderSnapshotHash,
        outboxEventRef: result.outboxEventId,
        completedAt: occurredAt,
        duplicate: result.duplicate,
      );
    } on PosDomainException catch (error) {
      throw PosSaleFailure(error.code, error.message);
    }
  }

  @override
  Future<PosPrintPreviewView> previewPrintTask(String orderRef) async {
    final binding = database.binding;
    final orders = database.database.select(
      '''SELECT o.*,p.print_job_id FROM local_order o
         JOIN local_print_job p ON p.tenant_id=o.tenant_id AND p.order_id=o.order_id
         WHERE o.tenant_id=? AND o.store_id=? AND o.terminal_id=? AND o.order_id=? AND o.status='COMPLETED' ''',
      [binding.tenantId, binding.storeId, binding.terminalId, orderRef],
    );
    if (orders.length != 1) {
      throw const PosSaleFailure('PRINT_TASK_NOT_FOUND', '打印任务不存在或不可见。');
    }
    final order = orders.single;
    final lines = database.database.select(
      'SELECT product_name_snapshot,quantity_decimal,payable_amount_minor FROM local_order_line WHERE tenant_id=? AND order_id=? ORDER BY line_no',
      [binding.tenantId, orderRef],
    );
    return PosPrintPreviewView(
      taskRef: order['print_job_id']! as String,
      orderRef: orderRef,
      title: '鲸熵汇收银小票（预览）',
      lines: lines
          .map(
            (line) =>
                '${line['product_name_snapshot']} × ${line['quantity_decimal']}  ${_money(line['payable_amount_minor']! as int)}',
          )
          .toList(growable: false),
      totalText: '合计 ${_money(order['receivable_amount_minor']! as int)}',
      adapterEvidence: 'FAKE_DEVICE_ADAPTER/BLOCKED_REAL_PRINTER',
    );
  }

  @override
  Future<PosSaleWorkspace> refreshSyncStatus() async {
    await syncCoordinator?.runOnce();
    return _workspace();
  }

  PosSaleWorkspace _workspace() {
    final basket = _basket ??= _newBasket();
    final quote = _currentQuote;
    final held = database.database.select(
      '''SELECT order_id,local_order_no,receivable_amount_minor,occurred_at,
         (SELECT COUNT(*) FROM local_order_line l WHERE l.tenant_id=o.tenant_id AND l.order_id=o.order_id) line_count
         FROM local_order o WHERE tenant_id=? AND store_id=? AND terminal_id=? AND cashier_id=?
           AND status='DRAFT' AND draft_disposition='SUSPENDED' ORDER BY occurred_at''',
      [
        database.binding.tenantId,
        database.binding.storeId,
        database.binding.terminalId,
        database.binding.cashierId,
      ],
    );
    final sync = _syncStatus();
    final discounts = quote?.lineDiscounts ?? const <String, int>{};
    final lineViews = basket.lines
        .map((line) {
          final discount = discounts[line.lineId] ?? 0;
          return PosBasketLineView(
            lineRef: line.lineId,
            productRef: line.quote.skuId,
            name: line.quote.productName,
            unitName:
                _products[line.quote.skuId]?.product.unitName ??
                line.quote.unitCode,
            quantity: line.quantity.canonical,
            unitPriceMinor: line.quote.unitPriceMinor,
            grossAmountMinor: line.grossAmountMinor,
            discountAmountMinor: discount,
            surchargeAmountMinor: 0,
            receivableAmountMinor: line.grossAmountMinor - discount,
            barcode: line.quote.barcode,
          );
        })
        .toList(growable: false);
    return PosSaleWorkspace(
      saleRef: basket.orderId,
      localSaleNo: basket.localOrderNo,
      lines: lineViews,
      totals: PosSaleTotals(
        grossAmountMinor: quote?.grossAmountMinor ?? basket.grossAmountMinor,
        discountAmountMinor: quote?.discountAmountMinor ?? 0,
        surchargeAmountMinor: 0,
        receivableAmountMinor:
            quote?.payableAmountMinor ?? basket.grossAmountMinor,
      ),
      quoteVersion: _baseQuote?.packageVersion ?? 0,
      quoteFingerprint: _settlementFingerprint ?? List.filled(64, '0').join(),
      businessDate: _requireOpenShift()['business_date']! as String,
      heldSales: held
          .map(
            (row) => PosHeldSaleView(
              saleRef: row['order_id']! as String,
              localSaleNo: row['local_order_no']! as String,
              lineCount: row['line_count']! as int,
              receivableAmountMinor: row['receivable_amount_minor']! as int,
              heldAt: DateTime.parse(row['occurred_at']! as String).toUtc(),
            ),
          )
          .toList(growable: false),
      syncStatus: sync,
      manualAuthorizationRef: _manualAuthorizationRef,
    );
  }

  PosSyncStatusView _syncStatus() {
    int count(String status) =>
        database.database.select(
              'SELECT COUNT(*) c FROM local_outbox WHERE tenant_id=? AND status=?',
              [database.binding.tenantId, status],
            ).single['c']!
            as int;
    final pending = count('PENDING');
    final retry = count('RETRY') + count('SENDING');
    final dead = count('DEAD_LETTER');
    final latest =
        database.database.select(
              "SELECT MAX(updated_at) at FROM local_outbox WHERE tenant_id=? AND status='ACKED'",
              [database.binding.tenantId],
            ).single['at']
            as String?;
    return PosSyncStatusView(
      online: syncCoordinator != null,
      pendingCount: pending,
      retryCount: retry,
      deadLetterCount: dead,
      lastSuccessfulAt: latest == null ? null : DateTime.parse(latest).toUtc(),
      safeMessage: syncCoordinator == null
          ? '同步网络未配置，交易保留在本地 Outbox'
          : dead > 0
          ? '存在隔离事件，需要人工处置'
          : pending + retry > 0
          ? '同步积压处理中'
          : '同步正常',
    );
  }

  List<PromotionLine> _promotionLines(Basket basket) => basket.lines
      .map((line) {
        final product =
            _products[line.quote.skuId] ??
            catalog.resolveSku(line.quote.skuId, at: _now().toUtc());
        _products[line.quote.skuId] = product;
        return PromotionLine(
          lineId: line.lineId,
          lineNo: line.lineNo,
          skuId: line.quote.skuId,
          categoryId: product.product.categoryId,
          brandId: product.product.brandId,
          quantity: ExactDecimal.parse(
            line.quantity.canonical,
            maximumScale: 6,
          ),
          unitPriceMinor: line.quote.unitPriceMinor,
        );
      })
      .toList(growable: false);

  PosProductView _productView(CatalogResolvedPrice value) => PosProductView(
    productRef: value.product.skuId,
    skuCode: value.product.skuCode,
    name: value.product.name,
    unitName: value.product.unitName,
    unitPriceMinor: value.amountMinor,
    barcode: value.product.barcode,
  );

  Basket _newBasket() {
    final id = ulids.next();
    return Basket(orderId: id, localOrderNo: 'POS-$id');
  }

  Basket _requireBasket() {
    final value = _basket;
    if (value == null) {
      throw const PosSaleFailure('SALE_WORKSPACE_NOT_LOADED', '请先加载收银工作区。');
    }
    return value;
  }

  dynamic _requireOpenShift() {
    final binding = database.binding;
    final rows = database.database.select(
      '''SELECT * FROM local_shift WHERE tenant_id=? AND store_id=? AND terminal_id=?
         AND cashier_id=? AND status='OPEN' ORDER BY opened_at DESC LIMIT 2''',
      [
        binding.tenantId,
        binding.storeId,
        binding.terminalId,
        binding.cashierId,
      ],
    );
    if (rows.length != 1) {
      throw const PosSaleFailure('SHIFT_NOT_OPEN', '当前终端和员工没有唯一的进行中班次。');
    }
    return rows.single;
  }

  void _clearQuote() {
    _baseQuote = null;
    _currentQuote = null;
    _settlementFingerprint = null;
    _sources = const {};
    _manualEvents.clear();
    _manualAuthorizationRef = null;
  }
}

int _max(int left, int right) => left > right ? left : right;

String _addWhole(String source, int whole) {
  final quantity = ExactQuantity.parse(source);
  final parts = quantity.canonical.split('.');
  final scale = parts.length == 1 ? 0 : parts[1].length;
  final digits = BigInt.parse(parts.join());
  final target = digits + BigInt.from(whole) * BigInt.from(10).pow(scale);
  if (target < BigInt.zero) {
    throw const PosSaleFailure('SALE_QUANTITY_INVALID', '商品数量不能小于零。');
  }
  if (scale == 0) return target.toString();
  final padded = target.toString().padLeft(scale + 1, '0');
  return ExactQuantity.parse(
    '${padded.substring(0, padded.length - scale)}.${padded.substring(padded.length - scale)}',
  ).canonical;
}

int _parseYuan(String source) {
  final match = RegExp(r'^(0|[1-9][0-9]{0,12})(?:\.([0-9]{1,2}))?$')
      .firstMatch(source.trim());
  if (match == null) {
    throw const PosSaleFailure('CASH_AMOUNT_INVALID', '现金金额格式不正确。');
  }
  final fraction = (match.group(2) ?? '').padRight(2, '0');
  return int.parse(match.group(1)!) * 100 +
      int.parse(fraction.isEmpty ? '0' : fraction);
}

String _money(int minor) {
  final yuan = minor ~/ 100;
  final fen = (minor % 100).toString().padLeft(2, '0');
  return '¥$yuan.$fen';
}
