import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/return_refund/application/pos_return_controller.dart';
import 'package:jshpos_pos/features/return_refund/domain/pos_return_models.dart';
import 'package:jshpos_pos/features/return_refund/infrastructure/locked_pos_return_application_service.dart';
import 'package:jshpos_pos/features/return_refund/presentation/pos_return_page.dart';

import 'pos_return_controller_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    TestWidgetsFlutterBinding.instance.platformDispatcher.views.first
      ..physicalSize = const Size(1440, 1100)
      ..devicePixelRatio = 1;
  });

  tearDown(() {
    TestWidgetsFlutterBinding.instance.platformDispatcher.views.first
      ..resetPhysicalSize()
      ..resetDevicePixelRatio();
  });

  testWidgets('查询原单、更新数量、二次确认并展示现金退货检查点', (tester) async {
    final fixture = await ReturnFixture.ready();
    await tester.pumpWidget(
      MaterialApp(home: PosReturnPage(controller: fixture.controller)),
    );

    await tester.enterText(find.byKey(const Key('returnOrderQuery')), orderRef);
    await tester.tap(find.byKey(const Key('searchOriginalOrder')));
    await tester.pumpAndSettle();

    expect(find.text('原单 SYN-20260820-0001'), findsOneWidget);
    expect(find.byKey(const Key('originalPromotionSnapshot')), findsOneWidget);
    expect(find.textContaining('当前最多可退 ¥12.00'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('returnQuantity:$lineRef')),
      '1',
    );
    await tester.tap(find.byKey(const Key('applyReturnQuantity:$lineRef')));
    await tester.pumpAndSettle();
    expect(find.text('现金退款 ¥6.00'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('returnSupervisorCredential')),
      'synthetic-supervisor',
    );
    await tester.tap(find.byKey(const Key('submitCashReturn')));
    await tester.pumpAndSettle();
    expect(find.text('确认原单退货退款'), findsOneWidget);
    await tester.tap(find.byKey(const Key('confirmReturnSubmit')));
    await tester.pumpAndSettle();

    expect(find.text('待独立审批'), findsOneWidget);
    expect(fixture.returns.submitCount, 1);
    expect(find.text('synthetic-supervisor'), findsNothing);
  });

  testWidgets('未知结果只允许查询原 returnRef 并最终收敛', (tester) async {
    final fixture = await ReturnFixture.ready();
    await fixture.controller.searchOriginalOrder(orderRef);
    await fixture.controller.changeQuantity(lineRef, '1');
    fixture.returns.failure = const PosReturnFailure(
      'RETURN_RESULT_UNKNOWN',
      '结果未知，请查询原申请。',
      resultUnknown: true,
      returnRef: returnRef,
    );
    await tester.pumpWidget(
      MaterialApp(home: PosReturnPage(controller: fixture.controller)),
    );

    await tester.tap(find.byKey(const Key('submitCashReturn')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('confirmReturnSubmit')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('recoverUnknownReturn')), findsOneWidget);
    expect(find.textContaining('禁止重新发起退款'), findsOneWidget);

    fixture.returns.failure = null;
    fixture.returns.nextStatus = PosReturnSagaStatus.completed;
    await tester.tap(find.byKey(const Key('recoverUnknownReturn')));
    await tester.pumpAndSettle();
    expect(find.text('退货退款完成'), findsOneWidget);
    expect(fixture.returns.submitCount, 1);
    expect(fixture.returns.refreshRef, returnRef);
  });

  testWidgets('未配置组合根时显示安全锁定错误', (tester) async {
    final fixture = await ReturnFixture.ready();
    final controller = PosReturnController(
      sessionService: fixture.session,
      returnService: const LockedPosReturnApplicationService(),
    );
    await tester.pumpWidget(
      MaterialApp(home: PosReturnPage(controller: controller)),
    );
    await tester.enterText(find.byKey(const Key('returnOrderQuery')), orderRef);
    await tester.tap(find.byKey(const Key('searchOriginalOrder')));
    await tester.pumpAndSettle();

    expect(find.textContaining('尚未完成安全配置'), findsOneWidget);
    expect(find.textContaining('RETURN_WORKSPACE_UNAVAILABLE'), findsOneWidget);
  });
}
