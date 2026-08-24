part of 'checkout_local_service.dart';

/// 班次开启、现金收支、钱箱事件与关班操作；事务及错误码保持原契约。
extension CheckoutLocalShiftOperations on CheckoutLocalService {
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
}
