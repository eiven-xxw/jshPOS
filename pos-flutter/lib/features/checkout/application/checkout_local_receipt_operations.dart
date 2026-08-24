part of 'checkout_local_service.dart';

/// 收据补打申请与审计操作；真实打印边界继续失败关闭。
extension CheckoutLocalReceiptOperations on CheckoutLocalService {
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
}
