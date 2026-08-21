import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/return_refund/application/pos_return_application_service.dart';
import 'package:jshpos_pos/features/return_refund/application/pos_return_controller.dart';
import 'package:jshpos_pos/features/return_refund/domain/pos_return_models.dart';
import 'package:jshpos_pos/features/return_refund/infrastructure/locked_pos_return_application_service.dart';
import 'package:jshpos_pos/features/session/application/pos_session_service.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';

import '../gate6d/pos_sale_controller_test.dart' show SaleFixture;

void main() {
  test('只读模型拒绝数量越界、摘要篡改和头行金额不守恒', () {
    expect(
      () => returnWorkspace(selected: true, promotionDigest: 'tampered'),
      throwsA(
        isA<PosReturnFailure>().having(
          (error) => error.code,
          'code',
          'RETURN_DIGEST_INVALID',
        ),
      ),
    );
    expect(
      () => returnLine(
        requestedQuantity: '3',
        requestedGrossMinor: 650,
        recoveredDiscountMinor: 50,
        refundableAmountMinor: 600,
      ),
      throwsA(
        isA<PosReturnFailure>().having(
          (error) => error.code,
          'code',
          'RETURN_QUANTITY_INVARIANT',
        ),
      ),
    );
    expect(
      () => returnWorkspace(selected: true, refundableAmountMinor: 601),
      throwsA(
        isA<PosReturnFailure>().having(
          (error) => error.code,
          'code',
          'RETURN_TOTAL_INVARIANT',
        ),
      ),
    );
  });

  test('搜索、改量和现金退货按单航班调用正式应用端口', () async {
    final fixture = await ReturnFixture.ready();

    await fixture.controller.searchOriginalOrder(orderRef);
    expect(fixture.controller.state.phase, PosReturnPagePhase.ready);
    expect(fixture.returns.findCount, 1);

    await fixture.controller.changeQuantity(lineRef, '1');
    expect(fixture.controller.state.workspace?.refundableAmountMinor, 600);

    final first = fixture.controller.submitCashReturn(
      reasonCode: 'CUSTOMER_REQUEST',
    );
    final second = fixture.controller.submitCashReturn(
      reasonCode: 'CUSTOMER_REQUEST',
    );
    expect(identical(first, second), isTrue);
    await Future.wait([first, second]);
    expect(fixture.returns.submitCount, 1);
    expect(fixture.controller.state.phase, PosReturnPagePhase.pending);
  });

  test('UNKNOWN 保留原 returnRef 且恢复只查询原申请', () async {
    final fixture = await ReturnFixture.ready();
    await fixture.controller.searchOriginalOrder(orderRef);
    await fixture.controller.changeQuantity(lineRef, '1');
    fixture.returns.failure = const PosReturnFailure(
      'RETURN_RESULT_UNKNOWN',
      '结果未知，请查询原申请。',
      resultUnknown: true,
      returnRef: returnRef,
    );

    await fixture.controller.submitCashReturn(reasonCode: 'CUSTOMER_REQUEST');
    expect(fixture.controller.state.phase, PosReturnPagePhase.unknown);
    expect(fixture.controller.state.recoverableReturnRef, returnRef);

    fixture.returns.failure = null;
    fixture.returns.nextStatus = PosReturnSagaStatus.completed;
    await fixture.controller.refreshStatus();
    expect(fixture.returns.refreshRef, returnRef);
    expect(fixture.returns.submitCount, 1);
    expect(fixture.controller.state.phase, PosReturnPagePhase.completed);
  });

  test('没有退货权限时失败关闭且不调用应用服务', () async {
    final fixture = await ReturnFixture.ready(
      permissions: {PosPermission.sessionLogin},
    );
    await fixture.controller.searchOriginalOrder(orderRef);
    expect(fixture.controller.state.errorCode, 'PERMISSION_DENIED');
    expect(fixture.returns.findCount, 0);
  });

  test('未知服务端状态不能映射成成功', () {
    expect(
      () => PosReturnSagaStatus.fromWire('NEW_PROVIDER_STATE'),
      throwsA(
        isA<PosReturnFailure>().having(
          (error) => error.code,
          'code',
          'RETURN_STATUS_UNSUPPORTED',
        ),
      ),
    );
  });

  test('输入、选择和原因在应用端口调用前失败关闭', () async {
    final fixture = await ReturnFixture.ready();
    await fixture.controller.searchOriginalOrder('');
    expect(fixture.controller.state.errorCode, 'RETURN_QUERY_INVALID');
    expect(fixture.returns.findCount, 0);

    await fixture.controller.changeQuantity(lineRef, '1');
    expect(fixture.controller.state.errorCode, 'RETURN_ORDER_REQUIRED');
    expect(fixture.returns.changeCount, 0);

    await fixture.controller.searchOriginalOrder(orderRef);
    await fixture.controller.submitCashReturn(reasonCode: 'CUSTOMER_REQUEST');
    expect(fixture.controller.state.errorCode, 'RETURN_SELECTION_REQUIRED');
    expect(fixture.returns.submitCount, 0);

    await fixture.controller.changeQuantity(lineRef, '1');
    await fixture.controller.submitCashReturn(reasonCode: 'bad reason');
    expect(fixture.controller.state.errorCode, 'RETURN_REASON_INVALID');
    expect(fixture.returns.submitCount, 0);

    fixture.controller.reset();
    expect(fixture.controller.state.phase, PosReturnPagePhase.idle);
  });

  test('默认退货组合根的所有能力都失败关闭', () async {
    const service = LockedPosReturnApplicationService();
    await expectLater(
      service.findOriginalOrder(orderRef),
      throwsA(isA<PosReturnFailure>()),
    );
    await expectLater(
      service.changeRequestedQuantity(lineRef, '1'),
      throwsA(isA<PosReturnFailure>()),
    );
    await expectLater(
      service.submitCashReturn(reasonCode: 'CUSTOMER_REQUEST'),
      throwsA(isA<PosReturnFailure>()),
    );
    await expectLater(
      service.refreshReturnStatus(returnRef),
      throwsA(isA<PosReturnFailure>()),
    );
  });

  test('非预期应用错误与恢复查询权限均失败关闭', () async {
    final fixture = await ReturnFixture.ready();

    fixture.returns.failure = StateError('synthetic search failure');
    await fixture.controller.searchOriginalOrder(orderRef);
    expect(fixture.controller.state.errorCode, 'RETURN_SEARCH_FAILED');

    fixture.returns.failure = null;
    await fixture.controller.searchOriginalOrder(orderRef);
    fixture.returns.failure = StateError('synthetic quantity failure');
    await fixture.controller.changeQuantity(lineRef, '1');
    expect(fixture.controller.state.errorCode, 'RETURN_QUANTITY_FAILED');

    fixture.returns.failure = null;
    await fixture.controller.changeQuantity(lineRef, '1');
    fixture.returns.failure = StateError('synthetic submit failure');
    await fixture.controller.submitCashReturn(reasonCode: 'CUSTOMER_REQUEST');
    expect(fixture.controller.state.errorCode, 'RETURN_SUBMIT_FAILED');

    fixture.returns.failure = StateError('synthetic refresh failure');
    await fixture.controller.refreshStatus(returnRef);
    expect(fixture.controller.state.phase, PosReturnPagePhase.unknown);
    expect(fixture.controller.state.recoverableReturnRef, returnRef);

    fixture.returns.failure = const PosReturnFailure(
      'RETURN_REFRESH_REJECTED',
      '合成查询被拒绝。',
    );
    await fixture.controller.refreshStatus(returnRef);
    expect(fixture.controller.state.errorCode, 'RETURN_REFRESH_REJECTED');

    final noReadPermission = await ReturnFixture.ready(
      permissions: {PosPermission.returnCreate},
    );
    await noReadPermission.controller.refreshStatus(returnRef);
    expect(noReadPermission.controller.state.errorCode, 'PERMISSION_DENIED');

    final noReference = await ReturnFixture.ready();
    await noReference.controller.refreshStatus();
    expect(noReference.controller.state.errorCode, 'RETURN_REFERENCE_REQUIRED');
  });
}

final class ReturnFixture {
  ReturnFixture._(this.session, this.returns)
    : controller = PosReturnController(
        sessionService: session,
        returnService: returns,
      );

  static Future<ReturnFixture> ready({Set<PosPermission>? permissions}) async {
    final saleFixture = await SaleFixture.ready(
      permissions:
          permissions ??
          {
            PosPermission.sessionLogin,
            PosPermission.returnRead,
            PosPermission.returnCreate,
            PosPermission.returnApprove,
          },
    );
    return ReturnFixture._(
      saleFixture.session,
      FakePosReturnApplicationService(),
    );
  }

  final PosSessionService session;
  final FakePosReturnApplicationService returns;
  final PosReturnController controller;
}

final class FakePosReturnApplicationService
    implements PosReturnApplicationService {
  Completer<PosReturnSubmissionView>? submitCompleter;
  Object? failure;
  PosReturnSagaStatus nextStatus = PosReturnSagaStatus.pendingApproval;
  int findCount = 0;
  int changeCount = 0;
  int submitCount = 0;
  int refreshCount = 0;
  String? refreshRef;

  void _fail() {
    if (failure != null) throw failure!;
  }

  @override
  Future<PosReturnWorkspace> findOriginalOrder(String orderQuery) async {
    findCount++;
    _fail();
    return returnWorkspace();
  }

  @override
  Future<PosReturnWorkspace> changeRequestedQuantity(
    String orderLineRef,
    String quantity,
  ) async {
    changeCount++;
    _fail();
    return returnWorkspace(selected: quantity != '0');
  }

  @override
  Future<PosReturnSubmissionView> submitCashReturn({
    required String reasonCode,
    String? supervisorCredential,
  }) {
    submitCount++;
    _fail();
    return submitCompleter?.future ??
        Future.value(returnSubmission(nextStatus));
  }

  @override
  Future<PosReturnSubmissionView> refreshReturnStatus(String returnRef) async {
    refreshCount++;
    refreshRef = returnRef;
    _fail();
    return returnSubmission(nextStatus);
  }
}

PosReturnWorkspace returnWorkspace({
  bool selected = false,
  String promotionDigest = digest,
  int? refundableAmountMinor,
}) {
  final line = selected
      ? returnLine(
          requestedQuantity: '1',
          requestedGrossMinor: 650,
          recoveredDiscountMinor: 50,
          refundableAmountMinor: 600,
        )
      : returnLine();
  return PosReturnWorkspace(
    orderRef: orderRef,
    localOrderNo: 'SYN-20260820-0001',
    storeName: '虚构便利一店',
    businessDate: '2026-08-20',
    currency: 'CNY',
    settlementKind: 'CASH',
    promotionSnapshotRef: promotionRef,
    promotionSnapshotSha256: promotionDigest,
    originalReceivableAmountMinor: 1200,
    cumulativeRefundedAmountMinor: 0,
    maximumRefundableAmountMinor: 1200,
    requestedGrossAmountMinor: selected ? 650 : 0,
    recoveredDiscountAmountMinor: selected ? 50 : 0,
    refundableAmountMinor: refundableAmountMinor ?? (selected ? 600 : 0),
    lines: [line],
  );
}

PosReturnLineView returnLine({
  String requestedQuantity = '0',
  int requestedGrossMinor = 0,
  int recoveredDiscountMinor = 0,
  int refundableAmountMinor = 0,
}) => PosReturnLineView(
  lineRef: lineRef,
  skuCode: 'SYN-COLA-001',
  name: '虚构可乐',
  unitName: '瓶',
  originalQuantity: '2',
  cumulativeReturnedQuantity: '0',
  maximumReturnableQuantity: '2',
  requestedQuantity: requestedQuantity,
  requestedGrossMinor: requestedGrossMinor,
  recoveredDiscountMinor: recoveredDiscountMinor,
  refundableAmountMinor: refundableAmountMinor,
);

PosReturnSubmissionView returnSubmission(PosReturnSagaStatus status) =>
    PosReturnSubmissionView(
      returnRef: returnRef,
      requestCommandRef: '01K2A000000000000000000043',
      orderRef: orderRef,
      status: status,
      refundableAmountMinor: 600,
      promotionSnapshotRef: promotionRef,
      promotionSnapshotSha256: digest,
      auditRef: 'AUDIT-SYNTHETIC-001',
      correlationRef: correlationRef,
      updatedAt: DateTime.utc(2026, 8, 20, 10),
      duplicate: false,
    );

const orderRef = '01K2A000000000000000000031';
const lineRef = '01K2A000000000000000000032';
const promotionRef = '01K2A000000000000000000033';
const returnRef = '01K2A000000000000000000041';
const correlationRef = '01K2A000000000000000000042';
const digest =
    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
