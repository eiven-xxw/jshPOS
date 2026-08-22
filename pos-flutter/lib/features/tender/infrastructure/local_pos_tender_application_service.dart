import 'dart:convert';

import 'package:crypto/crypto.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../checkout/domain/ulid_generator.dart';
import '../application/pos_tender_application_service.dart';
import '../domain/pos_tender_models.dart';

/// PAY-004 离线计划实现；冻结计划、份额、事件、审计和 Outbox 在同一 SQLite 事务。
///
/// 电子资金与现金确认均由服务端 Owner 完成；本类没有 Provider SDK/HTTP，也不会伪造成功。
final class LocalPosTenderApplicationService
    implements PosTenderApplicationService {
  LocalPosTenderApplicationService({
    required this.database,
    required this.ulids,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final PosLocalDatabase database;
  final UlidGenerator ulids;
  final DateTime Function() _now;

  @override
  Future<PosTenderPlanView> freeze({
    required PosTenderSource source,
    required List<PosTenderAllocationDraft> allocations,
  }) async {
    _requireTrustedSource(source);
    final normalized = PosTenderRules.validate(source, allocations);
    final existing = _findByOrder(source.orderRef);
    if (existing != null) {
      _requireSamePlan(existing, source, normalized);
      return _view(existing.planRef, duplicate: true);
    }
    final planRef = ulids.next();
    final commandRef = ulids.next();
    final allocationRefs = normalized.map((_) => ulids.next()).toList();
    final identities = List.generate(
      normalized.length,
      (index) => PosTenderAllocationIdentity(
        allocationRef: allocationRefs[index],
        sequenceNo: normalized[index].sequenceNo,
        tenderType: normalized[index].tenderType,
        amountMinor: normalized[index].amountMinor,
      ),
    );
    final at = _now().toUtc().toIso8601String();
    final content = <String, Object?>{
      'planId': planRef,
      'orderId': source.orderRef,
      'orderSnapshotSha256': source.orderSnapshotSha256,
      'storeId': source.storeRef,
      'terminalId': source.terminalRef,
      'shiftId': source.shiftRef,
      'businessDate': source.businessDate,
      'receivableAmountMinor': source.receivableAmountMinor,
      'currency': source.currency,
      'allocations': [
        for (var index = 0; index < normalized.length; index++)
          <String, Object>{
            'allocationId': allocationRefs[index],
            'sequenceNo': normalized[index].sequenceNo,
            'tenderType': normalized[index].tenderType.wire,
            'amountMinor': normalized[index].amountMinor,
          },
      ],
    };
    final contentHash = PosTenderDigest.planContentSha256(
      planRef: planRef,
      source: source,
      allocations: identities,
    );
    content['contentSha256'] = contentHash;
    final idempotencyKey = 'tender:freeze:${source.orderRef}';
    database.transaction(() {
      database.database.execute(
        '''INSERT INTO local_tender_plan(plan_id,tenant_id,order_id,order_snapshot_sha256,store_id,
           terminal_id,shift_id,business_date,status,receivable_amount_minor,succeeded_amount_minor,
           occupied_amount_minor,currency,allocation_count,content_sha256,command_id,idempotency_key,
           correlation_id,record_version,frozen_at,updated_at)
           VALUES(?,?,?,?,?,?,?,?, 'FROZEN',?,0,0,?,?,?,?,?,?,1,?,?)''',
        [
          planRef,
          database.binding.tenantId,
          source.orderRef,
          source.orderSnapshotSha256,
          source.storeRef,
          source.terminalRef,
          source.shiftRef,
          source.businessDate,
          source.receivableAmountMinor,
          source.currency,
          normalized.length,
          contentHash,
          commandRef,
          idempotencyKey,
          commandRef,
          at,
          at,
        ],
      );
      for (var index = 0; index < normalized.length; index++) {
        final item = normalized[index];
        database.database.execute(
          '''INSERT INTO local_tender_allocation(allocation_id,tenant_id,plan_id,sequence_no,tender_type,
             status,amount_minor,currency,allocation_sha256,record_version,created_at,updated_at)
             VALUES(?,?,?,?,?,'PLANNED',?,?,?,1,?,?)''',
          [
            allocationRefs[index],
            database.binding.tenantId,
            planRef,
            item.sequenceNo,
            item.tenderType.wire,
            item.amountMinor,
            source.currency,
            PosTenderDigest.allocationSha256(
              planRef: planRef,
              allocation: identities[index],
              currency: source.currency,
            ),
            at,
            at,
          ],
        );
      }
      final eventRef = ulids.next();
      database.database.execute(
        '''INSERT INTO local_tender_event(event_id,tenant_id,plan_id,allocation_id,event_type,status,
           command_id,payload_sha256,occurred_at) VALUES(?,?,?,NULL,'PLAN_FROZEN','FROZEN',?,?,?)''',
        [
          eventRef,
          database.binding.tenantId,
          planRef,
          commandRef,
          contentHash,
          at,
        ],
      );
      _audit(
        action: 'TENDER_PLAN_FROZEN',
        aggregateRef: planRef,
        commandRef: commandRef,
        afterStatus: 'FROZEN',
        amountMinor: source.receivableAmountMinor,
        requestHash: contentHash,
        at: at,
      );
      _outbox(
        eventRef: eventRef,
        planRef: planRef,
        commandRef: commandRef,
        idempotencyKey: idempotencyKey,
        content: content,
        contentHash: contentHash,
        at: at,
      );
      database.checkpoint('tender.freeze.before-commit');
    });
    return _view(planRef);
  }

  @override
  Future<PosTenderPlanView> find(String planRef) async => _view(planRef);

  @override
  Future<PosTenderPlanView> collect({
    required String planRef,
    required String allocationRef,
    int? tenderedMinor,
  }) async {
    final plan = _view(planRef);
    final target = plan.allocations
        .where((item) => item.allocationRef == allocationRef)
        .toList();
    if (target.length != 1) {
      throw const PosTenderFailure('TENDER-NOT-VISIBLE', '支付份额不存在或不可见。');
    }
    final item = target.single;
    if (item.status == PosTenderAllocationStatus.processing ||
        item.status == PosTenderAllocationStatus.unknown) {
      throw PosTenderFailure(
        'TENDER-UNKNOWN-001',
        '该份额结果未确定，只能刷新原计划。',
        resultUnknown: true,
        planRef: planRef,
      );
    }
    final previousBlocked = plan.allocations.any(
      (candidate) =>
          candidate.sequenceNo < item.sequenceNo &&
          candidate.status != PosTenderAllocationStatus.succeeded,
    );
    if (item.status != PosTenderAllocationStatus.planned || previousBlocked) {
      throw const PosTenderFailure('TENDER-SEQUENCE-002', '前序份额尚未成功，禁止越序收取。');
    }
    if (item.tenderType == PosTenderType.cash &&
        (tenderedMinor == null || tenderedMinor < item.amountMinor)) {
      throw const PosTenderFailure('TENDER-CASH-002', '现金实收不得小于冻结份额。');
    }
    final commandRef = ulids.next();
    final at = _now().toUtc().toIso8601String();
    final payloadHash = _hash(<String, Object?>{
      'planId': planRef,
      'allocationId': allocationRef,
      'tenderedMinor': tenderedMinor,
      'occurredAt': at,
    });
    database.transaction(() {
      database.database.execute(
        '''INSERT INTO local_tender_event(event_id,tenant_id,plan_id,allocation_id,event_type,status,
           command_id,payload_sha256,occurred_at) VALUES(?,?,?,?, ?,?,?,?,?)''',
        [
          ulids.next(),
          database.binding.tenantId,
          planRef,
          allocationRef,
          item.tenderType == PosTenderType.electronic
              ? 'BLOCKED_EXTERNAL'
              : 'SERVER_CONFIRMATION_REQUIRED',
          item.status.name.toUpperCase(),
          commandRef,
          payloadHash,
          at,
        ],
      );
      _audit(
        action: item.tenderType == PosTenderType.electronic
            ? 'TENDER_ELECTRONIC_BLOCKED'
            : 'TENDER_CASH_SERVER_CONFIRMATION_REQUIRED',
        aggregateRef: allocationRef,
        commandRef: commandRef,
        beforeStatus: item.status.name.toUpperCase(),
        afterStatus: item.status.name.toUpperCase(),
        amountMinor: item.amountMinor,
        requestHash: payloadHash,
        at: at,
      );
    });
    throw PosTenderFailure(
      item.tenderType == PosTenderType.electronic
          ? 'PAYMENT_EXTERNAL_BLOCKED'
          : 'TENDER-CASH-SERVER-001',
      item.tenderType == PosTenderType.electronic
          ? '电子支付资料尚未解阻，当前禁止执行。'
          : '组合支付现金份额必须在线由服务端原子确认。',
      planRef: planRef,
    );
  }

  void _requireTrustedSource(PosTenderSource source) {
    if (source.storeRef != database.binding.storeId ||
        source.terminalRef != database.binding.terminalId ||
        !_ulid(source.orderRef) ||
        !_ulid(source.shiftRef) ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(source.orderSnapshotSha256)) {
      throw const PosTenderFailure('TENDER-ORDER-001', '订单与可信门店终端上下文不匹配。');
    }
    final shifts = database.database.select(
      '''SELECT 1 FROM local_shift WHERE tenant_id=? AND store_id=? AND terminal_id=?
         AND cashier_id=? AND shift_id=? AND business_date=? AND status='OPEN' ''',
      [
        database.binding.tenantId,
        database.binding.storeId,
        database.binding.terminalId,
        database.binding.cashierId,
        source.shiftRef,
        source.businessDate,
      ],
    );
    if (shifts.length != 1) {
      throw const PosTenderFailure('TENDER-ORDER-001', '当前没有匹配的开放班次。');
    }
  }

  PosTenderPlanView? _findByOrder(String orderRef) {
    final rows = database.database.select(
      'SELECT plan_id FROM local_tender_plan WHERE tenant_id=? AND order_id=?',
      [database.binding.tenantId, orderRef],
    );
    return rows.isEmpty ? null : _view(rows.single['plan_id']! as String);
  }

  void _requireSamePlan(
    PosTenderPlanView existing,
    PosTenderSource source,
    List<PosTenderAllocationDraft> expected,
  ) {
    final frozen = database.database.select(
      '''SELECT order_snapshot_sha256,store_id,terminal_id,shift_id,business_date
         FROM local_tender_plan WHERE tenant_id=? AND plan_id=?''',
      [database.binding.tenantId, existing.planRef],
    );
    final same =
        frozen.length == 1 &&
        frozen.single['order_snapshot_sha256'] == source.orderSnapshotSha256 &&
        frozen.single['store_id'] == source.storeRef &&
        frozen.single['terminal_id'] == source.terminalRef &&
        frozen.single['shift_id'] == source.shiftRef &&
        frozen.single['business_date'] == source.businessDate &&
        existing.receivableAmountMinor == source.receivableAmountMinor &&
        existing.currency == source.currency &&
        existing.allocations.length == expected.length &&
        List.generate(expected.length, (index) {
          final old = existing.allocations[index];
          final current = expected[index];
          return old.sequenceNo == current.sequenceNo &&
              old.tenderType == current.tenderType &&
              old.amountMinor == current.amountMinor;
        }).every((value) => value);
    if (!same) {
      throw const PosTenderFailure('PAY-IDEMPOTENCY-001', '原订单已冻结为另一份支付计划。');
    }
  }

  PosTenderPlanView _view(String planRef, {bool duplicate = false}) {
    if (!_ulid(planRef)) {
      throw const PosTenderFailure('PAY-ID-001', '支付计划编号无效。');
    }
    final plans = database.database.select(
      '''SELECT * FROM local_tender_plan WHERE tenant_id=? AND store_id=? AND terminal_id=? AND plan_id=?''',
      [
        database.binding.tenantId,
        database.binding.storeId,
        database.binding.terminalId,
        planRef,
      ],
    );
    if (plans.length != 1) {
      throw const PosTenderFailure('TENDER-NOT-VISIBLE', '支付计划不存在或不可见。');
    }
    final plan = plans.single;
    final allocations = database.database.select(
      '''SELECT * FROM local_tender_allocation WHERE tenant_id=? AND plan_id=? ORDER BY sequence_no''',
      [database.binding.tenantId, planRef],
    );
    return PosTenderPlanView(
      planRef: planRef,
      orderRef: plan['order_id']! as String,
      status: _planStatus(plan['status']! as String),
      receivableAmountMinor: plan['receivable_amount_minor']! as int,
      succeededAmountMinor: plan['succeeded_amount_minor']! as int,
      occupiedAmountMinor: plan['occupied_amount_minor']! as int,
      currency: plan['currency']! as String,
      allocations: allocations
          .map((row) {
            return PosTenderAllocationView(
              allocationRef: row['allocation_id']! as String,
              sequenceNo: row['sequence_no']! as int,
              tenderType: row['tender_type'] == 'CASH'
                  ? PosTenderType.cash
                  : PosTenderType.electronic,
              status: _allocationStatus(row['status']! as String),
              amountMinor: row['amount_minor']! as int,
            );
          })
          .toList(growable: false),
      updatedAt: DateTime.parse(plan['updated_at']! as String).toUtc(),
      duplicate: duplicate,
    );
  }

  void _audit({
    required String action,
    required String aggregateRef,
    required String commandRef,
    String? beforeStatus,
    required String afterStatus,
    required int amountMinor,
    required String requestHash,
    required String at,
  }) {
    database.database.execute(
      '''INSERT INTO local_audit_event(audit_id,tenant_id,action_code,aggregate_type,aggregate_id,
         actor_id,approver_id,command_id,trace_id,before_status,after_status,amount_minor,currency,
         request_sha256,reason_code,occurred_at) VALUES(?,?,?,?,?,?,NULL,?,?,?,?,?,'CNY',?,?,?)''',
      [
        ulids.next(),
        database.binding.tenantId,
        action,
        action.startsWith('TENDER_PLAN') ? 'TENDER_PLAN' : 'TENDER_ALLOCATION',
        aggregateRef,
        database.binding.cashierId,
        commandRef,
        commandRef,
        beforeStatus,
        afterStatus,
        amountMinor,
        requestHash,
        action,
        at,
      ],
    );
  }

  void _outbox({
    required String eventRef,
    required String planRef,
    required String commandRef,
    required String idempotencyKey,
    required Map<String, Object?> content,
    required String contentHash,
    required String at,
  }) {
    final payload = <String, Object?>{
      ...content,
      'commandId': commandRef,
      'idempotencyKey': idempotencyKey,
    };
    final payloadJson = jsonEncode(payload);
    database.database.execute(
      '''INSERT INTO local_outbox(event_id,tenant_id,device_sequence,stream_code,event_type,aggregate_id,
         aggregate_version,correlation_id,payload_json,payload_sha256,status,attempt_count,created_at)
         VALUES(?,?,?,'order.command','tender.plan-frozen.v1',?,1,?,?,?,'PENDING',0,?)''',
      [
        eventRef,
        database.binding.tenantId,
        database.nextDeviceSequence(),
        planRef,
        commandRef,
        payloadJson,
        sha256.convert(utf8.encode(payloadJson)).toString(),
        at,
      ],
    );
  }

  String _hash(Object value) =>
      sha256.convert(utf8.encode(jsonEncode(value))).toString();
  bool _ulid(String value) =>
      RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(value);

  PosTenderPlanStatus _planStatus(String value) => switch (value) {
    'FROZEN' => PosTenderPlanStatus.frozen,
    'COLLECTING' => PosTenderPlanStatus.collecting,
    'UNKNOWN' => PosTenderPlanStatus.unknown,
    'PAID' => PosTenderPlanStatus.paid,
    'FAILED' => PosTenderPlanStatus.failed,
    'CANCELLED' => PosTenderPlanStatus.cancelled,
    'MANUAL_RECOVERY_REQUIRED' => PosTenderPlanStatus.manualRecoveryRequired,
    _ => throw const PosTenderFailure('TENDER-STATE-002', '支付计划状态无效。'),
  };

  PosTenderAllocationStatus _allocationStatus(String value) => switch (value) {
    'PLANNED' => PosTenderAllocationStatus.planned,
    'PROCESSING' => PosTenderAllocationStatus.processing,
    'UNKNOWN' => PosTenderAllocationStatus.unknown,
    'SUCCEEDED' => PosTenderAllocationStatus.succeeded,
    'FAILED' => PosTenderAllocationStatus.failed,
    'CANCELLED' => PosTenderAllocationStatus.cancelled,
    _ => throw const PosTenderFailure('TENDER-STATE-002', '支付份额状态无效。'),
  };
}
