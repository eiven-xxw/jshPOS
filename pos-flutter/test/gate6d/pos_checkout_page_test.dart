import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/sale/application/pos_sale_controller.dart';
import 'package:jshpos_pos/features/sale/infrastructure/locked_pos_sale_application_service.dart';
import 'package:jshpos_pos/features/sale/presentation/pos_checkout_page.dart';

import 'pos_sale_controller_test.dart' show SaleFixture;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    TestWidgetsFlutterBinding
        .instance
        .platformDispatcher
        .views
        .first
        .physicalSize = const Size(
      1440,
      1000,
    );
    TestWidgetsFlutterBinding
            .instance
            .platformDispatcher
            .views
            .first
            .devicePixelRatio =
        1;
  });

  tearDown(() {
    TestWidgetsFlutterBinding.instance.platformDispatcher.views.first
        .resetPhysicalSize();
    TestWidgetsFlutterBinding.instance.platformDispatcher.views.first
        .resetDevicePixelRatio();
  });

  testWidgets('扫码搜索改量报价人工优惠挂取单和同步状态形成正式触控旅程', (tester) async {
    final fixture = await SaleFixture.ready();
    await tester.pumpWidget(
      MaterialApp(home: PosCheckoutPage(controller: fixture.controller)),
    );
    await tester.pumpAndSettle();

    expect(find.text('收银工作台'), findsOneWidget);
    expect(find.textContaining('网络不可用'), findsOneWidget);
    expect(find.byKey(const Key('basketLines')), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('barcodeInput')),
      '690000000001',
    );
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();
    expect(fixture.sale.lastBarcode, '690000000001');

    await tester.enterText(find.byKey(const Key('productSearchInput')), '虚构可乐');
    await tester.tap(find.byKey(const Key('productSearchSubmit')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('searchResult:product:cola')), findsOneWidget);
    await tester.tap(find.text('¥6.50').last);
    await tester.pumpAndSettle();
    expect(fixture.controller.state.searchResults, isEmpty);

    await tester.tap(find.byKey(const Key('increase:line:cola')));
    await tester.pumpAndSettle();
    expect(fixture.sale.lastQuantity, '+1');
    await tester.tap(find.byKey(const Key('refreshPromotionQuote')));
    await tester.pumpAndSettle();
    expect(fixture.sale.refreshQuoteCount, 1);

    await tester.tap(find.byKey(const Key('manualAdjustment')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('manualAdjustmentValue')),
      '1.00',
    );
    await tester.enterText(
      find.byKey(const Key('supervisorCredential')),
      'synthetic-supervisor',
    );
    await tester.tap(find.byKey(const Key('manualAdjustmentSubmit')));
    await tester.pumpAndSettle();
    expect(fixture.sale.lastManualAction, 'ORDER_AMOUNT_OFF');

    await tester.tap(find.byKey(const Key('holdSale')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('取单'));
    await tester.pumpAndSettle();
    expect(fixture.sale.resumeRef, 'held:001');

    await tester.tap(find.byKey(const Key('refreshSyncStatus')));
    await tester.pumpAndSettle();
    expect(find.textContaining('同步正常'), findsOneWidget);
  });

  testWidgets('现金成交使用稳定幂等键并只生成打印预览', (tester) async {
    final fixture = await SaleFixture.ready();
    await tester.pumpWidget(
      MaterialApp(home: PosCheckoutPage(controller: fixture.controller)),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('cashSettlement')));
    await tester.pumpAndSettle();
    expect(find.text('应收 ¥12.00'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('cashTenderedInput')), '20.00');
    await tester.tap(find.byKey(const Key('cashTenderedSubmit')));
    await tester.pumpAndSettle();

    expect(find.text('现金收款成功'), findsOneWidget);
    expect(fixture.sale.idempotencyKey, contains('sale:001'));
    await tester.tap(find.byKey(const Key('previewPrintTask')));
    await tester.pumpAndSettle();
    expect(find.text('鲸熵汇收银小票预览'), findsOneWidget);
    expect(find.textContaining('仅预览'), findsOneWidget);
  });

  testWidgets('未配置正式应用组合根时显示安全锁定错误并允许重试', (tester) async {
    final fixture = await SaleFixture.ready();
    final controller = PosSaleController(
      sessionService: fixture.session,
      saleService: const LockedPosSaleApplicationService(),
    );
    await tester.pumpWidget(
      MaterialApp(home: PosCheckoutPage(controller: controller)),
    );
    await tester.pumpAndSettle();

    expect(find.textContaining('尚未完成安全配置'), findsOneWidget);
    expect(find.text('错误码：POS_WORKSPACE_UNAVAILABLE'), findsOneWidget);
    expect(find.byKey(const Key('retrySaleWorkspace')), findsOneWidget);
  });
}
