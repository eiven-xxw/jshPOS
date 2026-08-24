part of 'checkout_local_service.dart';

/// Checkout 的持久化、摘要、审计与幂等公共能力；不拥有新的业务状态。
mixin _CheckoutLocalPersistenceMixin {
  PosLocalDatabase get localDatabase;
  Database get _db;
  TrustedDeviceBinding get _binding;
  UlidGenerator get ulids;
  ShiftPolicy get shiftPolicy;

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

  void _requireDispositionInput(String reasonCode, String reasonText) {
    if (!RegExp(r'^[A-Z][A-Z0-9_]{1,31}$').hasMatch(reasonCode) ||
        reasonText.trim().isEmpty ||
        reasonText.length > 256) {
      throw const PosDomainException(
        'ORDER_DISPOSITION_INVALID',
        'reason code or text is invalid',
      );
    }
  }

  int _localCashPaymentCount(String orderId) =>
      _db.select(
            'SELECT COUNT(*) c FROM local_cash_payment WHERE tenant_id=? AND order_id=?',
            [_binding.tenantId, orderId],
          ).single['c']!
          as int;

  String _persistedOrderHash(String orderId) {
    final orders = _db.select(
      '''SELECT order_id,local_order_no,store_id,terminal_id,shift_id,cashier_id,
         business_date,store_timezone,status,draft_disposition,payment_status,currency,
         gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,
         received_amount_minor,record_version,snapshot_sha256
         FROM local_order WHERE tenant_id=? AND order_id=?''',
      [_binding.tenantId, orderId],
    );
    if (orders.length != 1) {
      throw const PosDomainException(
        'RESOURCE_NOT_VISIBLE',
        'order snapshot is unavailable',
      );
    }
    final order = orders.single;
    final frozenHash = order['snapshot_sha256'] as String?;
    if (frozenHash != null && RegExp(r'^[a-f0-9]{64}$').hasMatch(frozenHash)) {
      return frozenHash;
    }
    final lines = _db.select(
      '''SELECT line_id,line_no,sku_id,unit_id,quantity_decimal,unit_price_minor,
         gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor
         FROM local_order_line WHERE tenant_id=? AND order_id=? ORDER BY line_no''',
      [_binding.tenantId, orderId],
    );
    return _hash([
      order['order_id'],
      order['local_order_no'],
      order['store_id'],
      order['terminal_id'],
      order['shift_id'],
      order['cashier_id'],
      order['business_date'],
      order['store_timezone'],
      order['status'],
      order['draft_disposition'],
      order['payment_status'],
      order['currency'],
      order['gross_amount_minor'],
      order['discount_amount_minor'],
      order['surcharge_amount_minor'],
      order['receivable_amount_minor'],
      order['received_amount_minor'],
      order['record_version'],
      order['snapshot_sha256'],
      ...lines.expand(
        (line) => [
          line['line_id'],
          line['line_no'],
          line['sku_id'],
          line['unit_id'],
          line['quantity_decimal'],
          line['unit_price_minor'],
          line['gross_amount_minor'],
          line['discount_amount_minor'],
          line['surcharge_amount_minor'],
          line['payable_amount_minor'],
        ],
      ),
    ]);
  }

  String _dispositionHash({
    required String orderId,
    required String shiftId,
    required String businessDate,
    required String dispositionType,
    required String fromStatus,
    required String effectiveStatus,
    required String reasonCode,
    required String reasonText,
    String? authorizationRef,
    required String snapshotHash,
  }) => _hash([
    dispositionType,
    orderId,
    _binding.storeId,
    _binding.terminalId,
    _binding.cashierId,
    shiftId,
    businessDate,
    fromStatus,
    effectiveStatus,
    reasonCode,
    reasonText.trim(),
    authorizationRef ?? '',
    snapshotHash,
  ]);

  void _insertOrderDisposition({
    required String dispositionId,
    required String orderId,
    required String shiftId,
    required String businessDate,
    required String dispositionType,
    required String fromStatus,
    required String effectiveStatus,
    required String reasonCode,
    required String reasonText,
    String? authorizationRef,
    required String snapshotHash,
    required String commandId,
    required String idempotencyKey,
    required String requestHash,
    required int aggregateVersion,
    required String at,
  }) {
    _db.execute(
      '''INSERT INTO local_order_disposition(disposition_id,tenant_id,order_id,store_id,
         terminal_id,shift_id,cashier_id,business_date,disposition_type,from_status,
         effective_status,reason_code,reason_text,authorization_ref,order_snapshot_sha256,
         command_id,idempotency_key,request_sha256,aggregate_version,occurred_at)
         VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
      [
        dispositionId,
        _binding.tenantId,
        orderId,
        _binding.storeId,
        _binding.terminalId,
        shiftId,
        _binding.cashierId,
        businessDate,
        dispositionType,
        fromStatus,
        effectiveStatus,
        reasonCode,
        reasonText.trim(),
        authorizationRef,
        snapshotHash,
        commandId,
        idempotencyKey,
        requestHash,
        aggregateVersion,
        at,
      ],
    );
  }

  Map<String, Object?> _dispositionPayload({
    required String dispositionId,
    required String orderId,
    required String shiftId,
    required String businessDate,
    required String dispositionType,
    required String fromStatus,
    required String effectiveStatus,
    required String reasonCode,
    required String reasonText,
    String? authorizationRef,
    required String snapshotHash,
    required String requestHash,
    required int aggregateVersion,
    required String occurredAt,
  }) => {
    'dispositionId': dispositionId,
    'orderId': orderId,
    'storeId': _binding.storeId,
    'terminalId': _binding.terminalId,
    'cashierId': _binding.cashierId,
    'shiftId': shiftId,
    'businessDate': businessDate,
    'dispositionType': dispositionType,
    'fromStatus': fromStatus,
    'effectiveStatus': effectiveStatus,
    'reasonCode': reasonCode,
    'reasonText': reasonText.trim(),
    'authorizationRef': authorizationRef,
    'orderSnapshotSha256': snapshotHash,
    'requestSha256': requestHash,
    'aggregateVersion': aggregateVersion,
    'occurredAt': occurredAt,
  };

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

  /// 校验本地报价绑定、权益包和命令快照完全一致；缺失或摘要漂移时失败关闭。
  void _verifyMemberBenefitSettlement(PromotedCashSaleCommand command) {
    final rows = _db.select(
      'SELECT * FROM local_promotion_quote_member_benefit WHERE tenant_id=? AND quote_id=?',
      [_binding.tenantId, command.quoteId],
    );
    final snapshot = command.memberBenefitSnapshot;
    if (snapshot == null) {
      if (rows.isNotEmpty) {
        throw const PosDomainException(
          'MEMBER_BENEFIT_SNAPSHOT_REQUIRED',
          'member benefit quote requires its original snapshot',
        );
      }
      return;
    }
    if (rows.length != 1) {
      throw const PosDomainException(
        'MEMBER_BENEFIT_QUOTE_MISMATCH',
        'member benefit quote binding is missing',
      );
    }
    final row = rows.single;
    final versionsJson = jsonEncode(snapshot.memberPriceVersions);
    if (row['entitlement_snapshot_id'] != snapshot.entitlementSnapshotId ||
        row['benefit_version_id'] != snapshot.benefitVersionId ||
        row['selected_path'] != snapshot.selectedPath ||
        row['member_price_versions_json'] != versionsJson ||
        row['capability_config_version'] != snapshot.capabilityConfigVersion ||
        row['capability_sha256'] != snapshot.capabilitySha256 ||
        row['rights_digest'] != snapshot.rightsDigest ||
        row['explanation_sha256'] != snapshot.explanationSha256 ||
        row['package_version'] != snapshot.packageVersion ||
        row['package_sha256'] != snapshot.packageSha256 ||
        row['content_sha256'] != snapshot.contentSha256) {
      throw const PosDomainException(
        'MEMBER_BENEFIT_QUOTE_MISMATCH',
        'member benefit snapshot differs from frozen quote',
      );
    }
    final packages = _db.select(
      '''SELECT 1 FROM local_member_benefit_package_slot
        WHERE tenant_id=? AND store_id=? AND package_version=? AND payload_sha256=?
          AND state IN ('ACTIVE','RETIRED')''',
      [
        _binding.tenantId,
        _binding.storeId,
        snapshot.packageVersion,
        snapshot.packageSha256,
      ],
    );
    if (packages.length != 1) {
      throw const PosDomainException(
        'MEMBER_BENEFIT_PACKAGE_UNAVAILABLE',
        'quoted member benefit package is not retained',
      );
    }
  }

  /// 与订单、现金和 Outbox 同一 SQLite 事务冻结无 PII 原成交权益事实。
  void _insertOrderMemberBenefitSnapshot(
    PromotedCashSaleCommand command,
    String at,
  ) {
    final snapshot = command.memberBenefitSnapshot;
    if (snapshot == null) return;
    _db.execute(
      '''INSERT INTO local_order_member_benefit_snapshot(tenant_id,order_id,quote_id,
        entitlement_snapshot_id,benefit_version_id,selected_path,member_price_versions_json,
        capability_config_version,capability_sha256,rights_digest,explanation_sha256,
        package_version,package_sha256,content_sha256,occurred_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
      [
        _binding.tenantId,
        command.basket.orderId,
        command.quoteId,
        snapshot.entitlementSnapshotId,
        snapshot.benefitVersionId,
        snapshot.selectedPath,
        jsonEncode(snapshot.memberPriceVersions),
        snapshot.capabilityConfigVersion,
        snapshot.capabilitySha256,
        snapshot.rightsDigest,
        snapshot.explanationSha256,
        snapshot.packageVersion,
        snapshot.packageSha256,
        snapshot.contentSha256,
        at,
      ],
    );
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
        'INSERT INTO local_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,barcode_value,product_name_snapshot,unit_id,unit_code,quantity_decimal,unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor,price_source,measurement_snapshot_json,measurement_parse_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
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
          _measurementJson(line),
          line.quote.measuredSnapshot?.parseSha256,
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
        'INSERT INTO local_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,barcode_value,product_name_snapshot,unit_id,unit_code,quantity_decimal,unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor,price_source,measurement_snapshot_json,measurement_parse_sha256) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,?,?,?,?)',
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
          _measurementJson(line),
          line.quote.measuredSnapshot?.parseSha256,
        ],
      );
    }
  }

  void _verifyPersistedLines(Basket basket) {
    final persisted = _db.select(
      'SELECT line_no,sku_id,unit_id,quantity_decimal,unit_price_minor,gross_amount_minor,price_source,measurement_snapshot_json,measurement_parse_sha256 FROM local_order_line WHERE tenant_id=? AND order_id=? ORDER BY line_no',
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
          row['price_source'] != line.quote.priceSource ||
          row['measurement_snapshot_json'] != _measurementJson(line) ||
          row['measurement_parse_sha256'] !=
              line.quote.measuredSnapshot?.parseSha256) {
        throw const PosDomainException(
          'ORDER_AMOUNT_CHANGED',
          'persisted lines differ from resumed basket',
        );
      }
    }
  }

  /// 以与订单快照相同字段顺序冻结计量事实，标准商品保持 NULL。
  String? _measurementJson(BasketLine line) =>
      line.quote.measuredSnapshot == null
      ? null
      : jsonEncode(line.quote.measuredSnapshot!.toJson());

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
