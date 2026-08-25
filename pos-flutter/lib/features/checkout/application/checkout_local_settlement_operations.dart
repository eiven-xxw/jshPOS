part of 'checkout_local_service.dart';

/// 现金及促销成交编排；订单、收款、班次效果和 Outbox 仍在同一事务提交。
extension CheckoutLocalSettlementOperations on CheckoutLocalService {
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
      final lotSnapshot =
          LotCheckoutAllocator(
            database: localDatabase,
            ulids: ulids,
          ).freezeSale(
            basket: command.basket,
            businessDate: command.businessDate,
            industryTemplateVersion: command.industryTemplateVersion,
            commandId: command.commandId,
            occurredAt: at,
          );
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
          if (lotSnapshot != null)
            'lotSnapshotHash': 'sha256:${lotSnapshot.payloadSha256}',
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
      if (lotSnapshot != null) {
        _appendOutbox(
          stream: 'order.command',
          eventType: 'inventory.lot-sale.requested.v1',
          aggregateId: command.basket.orderId,
          aggregateVersion: completedVersion,
          correlationId: command.commandId,
          payload: {
            ...lotSnapshot.payload,
            'payloadSha256': 'sha256:${lotSnapshot.payloadSha256}',
          },
          occurredAt: at,
        );
      }
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
      _verifyMemberBenefitSettlement(command);
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
      final lotSnapshot =
          LotCheckoutAllocator(
            database: localDatabase,
            ulids: ulids,
          ).freezeSale(
            basket: command.basket,
            businessDate: command.businessDate,
            industryTemplateVersion: command.industryTemplateVersion,
            commandId: command.commandId,
            occurredAt: at,
          );
      localDatabase.checkpoint('promoted.order.snapshot');

      _insertPromotionSnapshot(command, promotionHash, at);
      localDatabase.checkpoint('promotion.snapshot');
      _insertOrderMemberBenefitSnapshot(command, at);
      localDatabase.checkpoint('member.benefit.snapshot');
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
          // 与收据冻结事件共享同一不可变打印任务身份，服务端不得重新生成替代。
          'printJobId': printJobId,
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
          if (command.memberBenefitSnapshot != null)
            'memberBenefitSnapshot': command.memberBenefitSnapshot!.toJson(),
          'orderSnapshotHash': 'sha256:$orderSnapshotHash',
          if (lotSnapshot != null)
            'lotSnapshotHash': 'sha256:${lotSnapshot.payloadSha256}',
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
          if (command.memberBenefitSnapshot != null)
            'memberBenefitSnapshot': command.memberBenefitSnapshot!.toJson(),
          'aggregateVersion': completedVersion,
          'orderSnapshotHash': 'sha256:$orderSnapshotHash',
        },
        occurredAt: at,
      );
      if (lotSnapshot != null) {
        _appendOutbox(
          stream: 'order.command',
          eventType: 'inventory.lot-sale.requested.v1',
          aggregateId: command.basket.orderId,
          aggregateVersion: completedVersion,
          correlationId: command.commandId,
          payload: {
            ...lotSnapshot.payload,
            'payloadSha256': 'sha256:${lotSnapshot.payloadSha256}',
          },
          occurredAt: at,
        );
      }
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
}
