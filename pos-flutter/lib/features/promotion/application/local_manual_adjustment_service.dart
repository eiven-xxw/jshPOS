import 'dart:convert';

import 'package:crypto/crypto.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../checkout/domain/promoted_order_snapshot_codec.dart';
import '../../checkout/domain/ulid_generator.dart';
import '../domain/manual_adjustment_engine.dart';
import '../domain/promotion_engine.dart';
import '../infrastructure/promotion_package_installer.dart';
import 'local_promotion_quote_service.dart';

/// 主管授权结果只保存内部人员引用和不可逆证明引用，不保存主管口令。
final class ManualApprovalEvidence {
  const ManualApprovalEvidence({
    required this.approverUserId,
    required this.authProofRef,
  });

  final String approverUserId;
  final String authProofRef;
}

/// 人工优惠复核端口；生产实现必须在可信员工会话边界验证主管凭据。
abstract interface class ManualApprovalPort {
  Future<ManualApprovalEvidence> authorize({
    required String operatorUserId,
    required String supervisorCredential,
    required int discountAmountMinor,
    required String correlationId,
  });
}

/// 未装配员工复核能力时失败关闭，不能把输入的主管口令当作授权成功。
final class RejectingManualApprovalPort implements ManualApprovalPort {
  const RejectingManualApprovalPort();

  @override
  Future<ManualApprovalEvidence> authorize({
    required String operatorUserId,
    required String supervisorCredential,
    required int discountAmountMinor,
    required String correlationId,
  }) async => throw StateError('PRM-AUTH-013: supervisor approval unavailable');
}

/// 只追加的本地人工优惠事实，随后由 Checkout Owner 以引用方式冻结到成交快照。
final class LocalManualAdjustmentResult {
  const LocalManualAdjustmentResult({
    required this.manualEventId,
    required this.previewFingerprint,
    required this.quote,
    required this.sourceAllocationsByLine,
    required this.approverUserId,
  });

  final String manualEventId;
  final String previewFingerprint;
  final PromotionQuote quote;
  final Map<String, Map<String, int>> sourceAllocationsByLine;
  final String? approverUserId;
}

/// 使用签名规则包中的阈值预检、复核并原子保存人工优惠与审计。
final class LocalManualAdjustmentService {
  LocalManualAdjustmentService({
    required this.database,
    required this.packageInstaller,
    required this.engine,
    required this.approvalPort,
    required this.ulids,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final PosLocalDatabase database;
  final PromotionPackageInstaller packageInstaller;
  final ManualAdjustmentEngine engine;
  final ManualApprovalPort approvalPort;
  final UlidGenerator ulids;
  final DateTime Function() _now;

  Future<LocalManualAdjustmentResult> apply({
    required LocalPromotionQuoteResult baseQuote,
    required PromotionQuote currentQuote,
    required String beforeFingerprint,
    required Map<String, Map<String, int>> currentSources,
    required List<PromotionLine> lines,
    required ManualActionType actionType,
    required String amountOrRate,
    required ManualPaymentMethod paymentMethod,
    required String businessDate,
    String? lineId,
    String? supervisorCredential,
  }) async {
    final binding = database.binding..validate();
    final active = await packageInstaller.requireActive();
    if (baseQuote.packageVersion != active.packageVersion ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(beforeFingerprint)) {
      throw StateError('PRM-AUTH-014: quote or package chain is stale');
    }
    final authorizationId = ulids.next();
    final commandId = ulids.next();
    final preview = engine.preview(
      current: currentQuote,
      lines: lines,
      command: ManualCommand(
        authorizationId: authorizationId,
        actionType: actionType,
        lineId: lineId,
        amountOrRate: amountOrRate,
        paymentMethod: paymentMethod,
      ),
      policy: ManualPolicy(
        policyVersionId: active.manualPolicy.policyVersionId,
        policySha256: active.manualPolicy.policySha256,
        withoutApprovalMinor: active.manualPolicy.withoutApprovalMinor,
        withApprovalMinor: active.manualPolicy.withApprovalMinor,
        minimumLinePayableMinor: active.manualPolicy.minimumLinePayableMinor,
        maximumRoundingMinor: active.manualPolicy.maximumRoundingMinor,
        roundingMultiplesMinor: active.manualPolicy.roundingMultiplesMinor,
      ),
    );
    ManualApprovalEvidence? approval;
    if (preview.requiresApproval) {
      final credential = supervisorCredential ?? '';
      if (credential.isEmpty) {
        throw StateError('PRM-AUTH-015: supervisor approval required');
      }
      approval = await approvalPort.authorize(
        operatorUserId: binding.cashierId,
        supervisorCredential: credential,
        discountAmountMinor: preview.incrementalDiscountMinor,
        correlationId: commandId,
      );
      if (approval.approverUserId == binding.cashierId ||
          approval.approverUserId.isEmpty ||
          approval.authProofRef.isEmpty) {
        throw StateError('PRM-AUTH-016: four-eyes approval is invalid');
      }
    }
    final fingerprint = _fingerprint(preview.result);
    final sources = <String, Map<String, int>>{
      for (final entry in currentSources.entries)
        entry.key: Map<String, int>.from(entry.value),
    };
    for (final adjustment in preview.result.adjustments.where(
      (value) => value.sourceId == authorizationId,
    )) {
      for (final allocation in adjustment.lineAllocations.entries) {
        (sources[allocation.key] ??= {})['MANUAL:$authorizationId'] =
            allocation.value;
      }
    }
    final result = <String, Object?>{
      'grossAmountMinor': preview.result.grossAmountMinor,
      'discountAmountMinor': preview.result.discountAmountMinor,
      'payableAmountMinor': preview.result.payableAmountMinor,
      'lineDiscounts': preview.result.lineDiscounts,
      'sourceAllocationsByLine': sources,
      if (approval != null) 'authProofRef': approval.authProofRef,
    };
    final resultJson = PromotedOrderSnapshotCodec.canonicalJson(result);
    final resultSha256 = sha256.convert(utf8.encode(resultJson)).toString();
    final requestSha256 = sha256
        .convert(
          utf8.encode(
            PromotedOrderSnapshotCodec.canonicalJson({
              'quoteId': baseQuote.quoteId,
              'beforeFingerprint': beforeFingerprint,
              'actionType': actionType.name,
              'lineId': lineId,
              'amountOrRate': amountOrRate,
              'paymentMethod': paymentMethod.name,
              'operatorUserId': binding.cashierId,
            }),
          ),
        )
        .toString();
    final eventId = ulids.next();
    final occurredAt = _now().toUtc().toIso8601String();
    database.transaction(() {
      database.database.execute(
        '''INSERT INTO local_promotion_manual_event(
          manual_event_id,tenant_id,authorization_id,event_sequence,state,command_id,request_sha256,
          quote_id,store_id,terminal_id,action_type,source_line_id,amount_or_rate,payment_method,
          before_fingerprint,preview_fingerprint,incremental_discount_minor,package_version,
          policy_version_id,policy_sha256,operator_user_id,approver_user_id,business_date,reason_code,
          reason_text,correlation_id,result_json,result_sha256,occurred_at)
          VALUES(?,?,?,1,'APPLIED',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
        [
          eventId,
          binding.tenantId,
          authorizationId,
          commandId,
          requestSha256,
          baseQuote.quoteId,
          binding.storeId,
          binding.terminalId,
          _wireAction(actionType),
          lineId,
          amountOrRate,
          paymentMethod == ManualPaymentMethod.cash ? 'CASH' : 'NON_CASH',
          beforeFingerprint,
          fingerprint,
          preview.incrementalDiscountMinor,
          active.packageVersion,
          active.manualPolicy.policyVersionId,
          active.manualPolicy.policySha256,
          binding.cashierId,
          approval?.approverUserId,
          businessDate,
          approval == null ? 'POLICY_AUTO_APPLIED' : 'SUPERVISOR_APPROVED',
          approval == null
              ? 'within signed policy threshold'
              : 'four-eyes approval verified',
          commandId,
          resultJson,
          resultSha256,
          occurredAt,
        ],
      );
      database.database.execute(
        '''INSERT INTO local_audit_event(audit_id,tenant_id,action_code,aggregate_type,aggregate_id,
          actor_id,approver_id,command_id,trace_id,before_status,after_status,amount_minor,currency,
          request_sha256,reason_code,occurred_at)
          VALUES(?,?,'PROMOTION_MANUAL_APPLIED','PROMOTION_QUOTE',?,?,?,?,?,'CALCULATED','ADJUSTED',
          ?,'CNY',?,?,?)''',
        [
          ulids.next(),
          binding.tenantId,
          baseQuote.quoteId,
          binding.cashierId,
          approval?.approverUserId,
          commandId,
          commandId,
          preview.incrementalDiscountMinor,
          requestSha256,
          approval == null ? 'POLICY_AUTO_APPLIED' : 'SUPERVISOR_APPROVED',
          occurredAt,
        ],
      );
    });
    return LocalManualAdjustmentResult(
      manualEventId: eventId,
      previewFingerprint: fingerprint,
      quote: preview.result,
      sourceAllocationsByLine: {
        for (final entry in sources.entries)
          entry.key: Map.unmodifiable(entry.value),
      },
      approverUserId: approval?.approverUserId,
    );
  }

  String _wireAction(ManualActionType value) => switch (value) {
    ManualActionType.lineFixedPrice => 'LINE_FIXED_PRICE',
    ManualActionType.orderAmountOff => 'ORDER_AMOUNT_OFF',
    ManualActionType.orderPercentOff => 'ORDER_PERCENT_OFF',
    ManualActionType.rounding => 'ROUNDING',
  };

  String _fingerprint(PromotionQuote value) => sha256
      .convert(
        utf8.encode(
          PromotedOrderSnapshotCodec.canonicalJson({
            'engineVersion': PromotionEngine.engineVersion,
            'grossAmountMinor': value.grossAmountMinor,
            'discountAmountMinor': value.discountAmountMinor,
            'payableAmountMinor': value.payableAmountMinor,
            'lineDiscounts': value.lineDiscounts,
            'appliedRuleIds': value.appliedRuleIds,
          }),
        ),
      )
      .toString();
}
