import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:jshpos_pos/features/shift/application/pos_shift_application_service.dart';
import 'package:jshpos_pos/features/shift/domain/shift_models.dart';
import 'package:jshpos_pos/features/shift/presentation/pos_cash_management_page.dart';

void main() {
  group('G9A-R3C R6 FLT-05 班次现金与钱箱页', () {
    testWidgets('冻结班次业务日并明确真实钱箱外部阻断', (tester) async {
      final service = _FakeShiftService();
      await tester.pumpWidget(
        MaterialApp(
          home: PosCashManagementPage(
            shiftId: 'SHIFT-R3C-001',
            businessDate: '2026-08-25',
            service: service,
            allowCashMovement: false,
            allowDrawerRequest: false,
          ),
        ),
      );

      expect(find.byKey(const Key('cashFrozenContext')), findsOneWidget);
      expect(find.textContaining('2026-08-25'), findsOneWidget);
      expect(
        find.textContaining('BLOCKED_EXTERNAL / UNAVAILABLE'),
        findsOneWidget,
      );
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const Key('recordShiftCashMovement')),
            )
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<OutlinedButton>(
              find.byKey(const Key('requestNoSaleDrawer')),
            )
            .onPressed,
        isNull,
      );
    });

    testWidgets('失败恢复复用原幂等键且重复点击受单航班保护', (tester) async {
      final service = _FakeShiftService(failFirstCash: true);
      await tester.pumpWidget(
        MaterialApp(
          home: PosCashManagementPage(
            shiftId: 'SHIFT-R3C-002',
            businessDate: '2026-08-25',
            service: service,
            allowCashMovement: true,
            allowDrawerRequest: true,
          ),
        ),
      );
      await tester.enterText(find.byType(TextField).at(0), '12.34');
      await tester.enterText(find.byType(TextField).at(1), '虚构备用金调整');

      Future<void> submit() async {
        await tester.tap(find.byKey(const Key('recordShiftCashMovement')));
        await tester.pumpAndSettle();
        expect(find.textContaining('班次 SHIFT-R3C-002'), findsOneWidget);
        await tester.tap(find.byKey(const Key('confirmCashOperation')));
        await tester.pumpAndSettle();
      }

      await submit();
      expect(find.textContaining('原操作键已保留'), findsOneWidget);
      await submit();

      expect(service.cashKeys, hasLength(2));
      expect(service.cashKeys[1], service.cashKeys[0]);
      expect(find.textContaining('当前理论现金'), findsOneWidget);
    });
  });
}

final class _FakeShiftService implements PosShiftApplicationService {
  _FakeShiftService({this.failFirstCash = false});

  final bool failFirstCash;
  final List<String> cashKeys = <String>[];

  @override
  Future<ShiftOperationResult> recordCashMovement({
    required String shiftId,
    required ShiftCashMovementType movementType,
    required String amount,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async {
    cashKeys.add(idempotencyKey);
    if (failFirstCash && cashKeys.length == 1) {
      throw const PosSessionFailure('SHIFT_WRITE_FAILED', '本地事务未完成');
    }
    return ShiftOperationResult(
      operationId: 'OP-R3C-001',
      shiftId: shiftId,
      operationType: movementType.wireCode,
      theoreticalCashMinor: 11234,
      recordVersion: 2,
      deviceExecutionStatus: 'NOT_REQUIRED',
    );
  }

  @override
  Future<ShiftOperationResult> requestNoSaleDrawer({
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async => ShiftOperationResult(
    operationId: 'DRAWER-R3C-001',
    shiftId: shiftId,
    operationType: 'NO_SALE_DRAWER',
    theoreticalCashMinor: 10000,
    recordVersion: 2,
    deviceExecutionStatus: 'BLOCKED_EXTERNAL',
  );

  @override
  Future<PosShiftContext> open({
    required String businessDate,
    required String openingCash,
    required String idempotencyKey,
  }) => throw UnimplementedError();

  @override
  Future<void> close({
    required String shiftId,
    required String actualCash,
    required String idempotencyKey,
  }) => throw UnimplementedError();
}
