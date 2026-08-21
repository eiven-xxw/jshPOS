import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/sale/application/pos_sale_application_service.dart';
import 'package:jshpos_pos/features/sale/application/pos_sale_controller.dart';
import 'package:jshpos_pos/features/sale/domain/pos_sale_models.dart';
import 'package:jshpos_pos/features/sale/infrastructure/locked_pos_sale_application_service.dart';
import 'package:jshpos_pos/features/session/application/pos_session_repository.dart';
import 'package:jshpos_pos/features/session/application/pos_session_service.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

void main() {
  group('T2-POS-008 收银页面编排器', () {
    test('初始化、扫码、搜索、加购、改量、报价、优惠和同步只调用应用端口', () async {
      final fixture = await SaleFixture.ready();
      final controller = fixture.controller;

      expect((await controller.initialize()).phase, PosSalePagePhase.ready);
      await controller.scan('690000000001');
      expect(fixture.sale.lastBarcode, '690000000001');
      await controller.search('可乐');
      expect(controller.state.searchResults.single.name, '虚构可乐');
      await controller.addProduct('product:cola');
      expect(controller.state.searchResults, isEmpty);
      await controller.changeQuantity('line:cola', '+1');
      await controller.refreshQuote();
      await controller.applyManualAdjustment(
        actionCode: 'ORDER_AMOUNT_OFF',
        value: '1.00',
        supervisorCredential: 'synthetic-supervisor',
      );
      await controller.refreshSync();

      expect(fixture.sale.lastQuantity, '+1');
      expect(fixture.sale.lastManualAction, 'ORDER_AMOUNT_OFF');
      expect(fixture.sale.lastSupervisor, 'synthetic-supervisor');
      expect(fixture.sale.refreshQuoteCount, 1);
      expect(fixture.sale.refreshSyncCount, 1);
    });

    test('挂单、取单、现金成交、打印预览和下一单保留稳定业务引用', () async {
      final fixture = await SaleFixture.ready();
      final controller = fixture.controller;
      await controller.initialize();

      await controller.hold();
      await controller.resume('held:001');
      final settlement = await controller.settleCash(
        tenderedAmount: '20.00',
        idempotencyKey: 'cash:sale:001:fingerprint',
      );
      final preview = await controller.loadPrintPreview('order:001');
      final reprint = await controller.requestReprint(
        orderRef: 'order:001',
        reasonCode: 'CUSTOMER_COPY',
        reasonText: '顾客要求补打',
        idempotencyKey: 'reprint:order:001:0001',
      );
      final next = await controller.startNextSale();

      expect(fixture.sale.resumeRef, 'held:001');
      expect(fixture.sale.idempotencyKey, 'cash:sale:001:fingerprint');
      expect(settlement.settlement?.orderRef, 'order:001');
      expect(preview.printPreview?.adapterEvidence, 'FAKE_DEVICE_ADAPTER');
      expect(reprint.reprintRequest?.executionStatus, 'BLOCKED_EXTERNAL');
      expect(next.phase, PosSalePagePhase.ready);
      expect(fixture.sale.loadCount, 2);
    });

    test('交易取消与成交后退货入口执行独立权限并只调用应用端口', () async {
      final fixture = await SaleFixture.ready();
      await fixture.controller.initialize();

      final cancelled = await fixture.controller.cancelCurrent(
        reasonCode: 'CUSTOMER_CANCELLED',
        reasonText: '虚构顾客付款前取消',
      );
      final held = await fixture.controller.cancelHeld(
        saleRef: 'held:001',
        reasonCode: 'HELD_ORDER_ABANDONED',
        reasonText: '虚构顾客放弃挂单',
      );
      final routed = await fixture.controller.routeCompletedSaleToReturn(
        'order:001',
      );

      expect(cancelled.workspace?.lines, isEmpty);
      expect(held.phase, PosSalePagePhase.ready);
      expect(fixture.sale.cancelledSaleRef, 'held:001');
      expect(fixture.sale.cancelReasonCode, 'HELD_ORDER_ABANDONED');
      expect(routed.disposition?.effectiveStatus, 'COMPLETED');
      expect(fixture.sale.routedOrderRef, 'order:001');

      final denied = await SaleFixture.ready(
        permissions: {PosPermission.sessionLogin, PosPermission.saleOperate},
      );
      await denied.controller.initialize();
      expect(
        (await denied.controller.cancelCurrent(
          reasonCode: 'ENTRY_ERROR',
          reasonText: '不得执行',
        )).errorCode,
        'PERMISSION_DENIED',
      );
      expect(denied.sale.cancelCount, 0);
    });

    test('重复扫码共享同一航班且只产生一次应用服务调用', () async {
      final completer = Completer<PosSaleWorkspace>();
      final fixture = await SaleFixture.ready(scanCompleter: completer);
      await fixture.controller.initialize();

      final first = fixture.controller.scan('690000000001');
      final second = fixture.controller.scan('690000000001');
      completer.complete(saleWorkspace());
      await Future.wait([first, second]);

      expect(fixture.sale.scanCount, 1);
    });

    test('短搜索不访问仓储，权限拒绝、已知异常和未知异常安全收敛', () async {
      final fixture = await SaleFixture.ready();
      await fixture.controller.initialize();
      expect((await fixture.controller.search('x')).searchResults, isEmpty);
      expect(fixture.sale.searchCount, 0);

      fixture.sale.failure = const PosSaleFailure(
        'PRODUCT_NOT_FOUND',
        '商品不存在。',
      );
      final known = await fixture.controller.scan('missing');
      expect(known.errorCode, 'PRODUCT_NOT_FOUND');
      fixture.sale.failure = StateError('synthetic secret');
      final unknown = await fixture.controller.refreshQuote();
      expect(unknown.errorCode, 'POS_OPERATION_FAILED');
      expect('$unknown', isNot(contains('synthetic secret')));

      final denied = await SaleFixture.ready(
        permissions: {PosPermission.sessionLogin},
      );
      final deniedState = await denied.controller.initialize();
      expect(deniedState.errorCode, 'PERMISSION_DENIED');
      expect(denied.sale.loadCount, 0);
    });

    test('搜索、成交与预览异常使用各自安全错误码', () async {
      final fixture = await SaleFixture.ready();
      await fixture.controller.initialize();
      fixture.sale.failure = StateError('synthetic detail');

      expect(
        (await fixture.controller.search('虚构')).errorCode,
        'POS_SEARCH_FAILED',
      );
      expect(
        (await fixture.controller.settleCash(
          tenderedAmount: '20.00',
          idempotencyKey: 'cash:stable',
        )).errorCode,
        'CASH_SETTLEMENT_FAILED',
      );
      expect(
        (await fixture.controller.loadPrintPreview('order:001')).errorCode,
        'PRINT_PREVIEW_FAILED',
      );
    });
  });

  group('T2-POS-008 页面模型与默认失败关闭端口', () {
    test('金额不守恒拒绝进入页面，状态和同步派生值保持确定', () {
      expect(
        () => PosSaleTotals(
          grossAmountMinor: 1000,
          discountAmountMinor: 100,
          surchargeAmountMinor: 0,
          receivableAmountMinor: 1000,
        ),
        throwsA(
          isA<PosSaleFailure>().having(
            (error) => error.code,
            'code',
            'SALE_AMOUNT_INVARIANT',
          ),
        ),
      );
      expect(saleWorkspace().canSettle, isTrue);
      expect(saleWorkspace().syncStatus.backlogCount, 3);
      expect(const PosSaleFailure('SAFE', '安全信息').toString(), 'SAFE: 安全信息');
      expect(const PosSalePageState.loading().busy, isTrue);
    });

    test('未配置正式组合根时所有收银能力失败关闭', () async {
      const service = LockedPosSaleApplicationService();
      final calls = <Future<Object?> Function()>[
        service.loadWorkspace,
        () => service.scanBarcode('690000000001'),
        () => service.searchProducts('可乐'),
        () => service.addProduct('product:cola'),
        () => service.changeQuantity('line:cola', '+1'),
        service.refreshPromotionQuote,
        () => service.applyManualAdjustment(
          actionCode: 'ORDER_AMOUNT_OFF',
          value: '1.00',
        ),
        service.holdCurrentSale,
        () => service.resumeHeldSale('held:001'),
        () => service.cancelCurrentSale(
          reasonCode: 'ENTRY_ERROR',
          reasonText: '虚构取消',
        ),
        () => service.cancelHeldSale(
          saleRef: 'held:001',
          reasonCode: 'HELD_ORDER_ABANDONED',
          reasonText: '虚构取消',
        ),
        () => service.settleCash(
          tenderedAmount: '20.00',
          idempotencyKey: 'cash:stable',
        ),
        () => service.previewPrintTask('order:001'),
        () => service.requestReceiptReprint(
          orderRef: 'order:001',
          reasonCode: 'CUSTOMER_COPY',
          reasonText: '顾客要求补打',
          idempotencyKey: 'reprint:order:001:0001',
        ),
        () => service.routeCompletedSaleToReturn('order:001'),
        service.refreshSyncStatus,
      ];

      for (final call in calls) {
        await expectLater(
          call,
          throwsA(
            isA<PosSaleFailure>().having(
              (error) => error.code,
              'code',
              'POS_WORKSPACE_UNAVAILABLE',
            ),
          ),
        );
      }
    });
  });
}

final fixtureNow = DateTime.utc(2026, 8, 20, 8);

PosSaleWorkspace saleWorkspace({
  List<PosBasketLineView>? lines,
  List<PosHeldSaleView>? heldSales,
  PosSyncStatusView? syncStatus,
}) => PosSaleWorkspace(
  saleRef: 'sale:001',
  localSaleNo: 'POS-20260820-0001',
  lines:
      lines ??
      const [
        PosBasketLineView(
          lineRef: 'line:cola',
          productRef: 'product:cola',
          name: '虚构可乐',
          unitName: '瓶',
          quantity: '2',
          unitPriceMinor: 650,
          grossAmountMinor: 1300,
          discountAmountMinor: 100,
          surchargeAmountMinor: 0,
          receivableAmountMinor: 1200,
          barcode: '690000000001',
        ),
      ],
  totals: PosSaleTotals(
    grossAmountMinor: lines?.isEmpty == true ? 0 : 1300,
    discountAmountMinor: lines?.isEmpty == true ? 0 : 100,
    surchargeAmountMinor: 0,
    receivableAmountMinor: lines?.isEmpty == true ? 0 : 1200,
  ),
  quoteVersion: 7,
  quoteFingerprint:
      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  businessDate: '2026-08-20',
  heldSales:
      heldSales ??
      [
        PosHeldSaleView(
          saleRef: 'held:001',
          localSaleNo: 'POS-20260820-0000',
          lineCount: 1,
          receivableAmountMinor: 500,
          heldAt: fixtureNow,
        ),
      ],
  syncStatus:
      syncStatus ??
      const PosSyncStatusView(
        online: false,
        pendingCount: 2,
        retryCount: 1,
        deadLetterCount: 0,
        lastSuccessfulAt: null,
        safeMessage: '网络不可用，交易将安全保存在本机。',
      ),
);

final product = const PosProductView(
  productRef: 'product:cola',
  skuCode: 'SKU-COLA',
  name: '虚构可乐',
  unitName: '瓶',
  unitPriceMinor: 650,
  barcode: '690000000001',
  stockHint: '库存充足',
);

final settlement = PosCashSettlementView(
  orderRef: 'order:001',
  localOrderNo: 'POS-20260820-0001',
  receivableAmountMinor: 1200,
  tenderedAmountMinor: 2000,
  changeAmountMinor: 800,
  snapshotDigest:
      'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
  outboxEventRef: 'event:001',
  completedAt: fixtureNow,
  duplicate: false,
);

const printPreview = PosPrintPreviewView(
  taskRef: 'print:001',
  orderRef: 'order:001',
  title: '鲸熵汇收银小票预览',
  lines: ['虚构可乐 x2  ¥13.00', '优惠  -¥1.00'],
  totalText: '应收 ¥12.00',
  adapterEvidence: 'FAKE_DEVICE_ADAPTER',
);

final class SaleFixture {
  SaleFixture._(this.session, this.sale)
    : controller = PosSaleController(
        sessionService: session,
        saleService: sale,
      );

  static Future<SaleFixture> ready({
    Set<PosPermission>? permissions,
    Completer<PosSaleWorkspace>? scanCompleter,
  }) async {
    final session = PosSessionService(
      deviceGateway: const _DeviceGateway(),
      repository: _SessionRepository(
        permissions:
            permissions ??
            {
              PosPermission.sessionLogin,
              PosPermission.saleOperate,
              PosPermission.manualDiscount,
              PosPermission.cashSettle,
              PosPermission.printPreview,
              PosPermission.printReprint,
              PosPermission.orderCancel,
              PosPermission.orderDispose,
              PosPermission.syncView,
            },
      ),
      correlationId: () => '01K2A000000000000000000099',
      now: () => fixtureNow,
    );
    await session.bootstrap();
    await session.login(loginName: 'cashier01', secret: 'synthetic-pin');
    return SaleFixture._(
      session,
      FakePosSaleApplicationService(scanCompleter: scanCompleter),
    );
  }

  final PosSessionService session;
  final FakePosSaleApplicationService sale;
  final PosSaleController controller;
}

final class FakePosSaleApplicationService implements PosSaleApplicationService {
  FakePosSaleApplicationService({this.scanCompleter});

  final Completer<PosSaleWorkspace>? scanCompleter;
  Object? failure;
  int loadCount = 0;
  int scanCount = 0;
  int searchCount = 0;
  int refreshQuoteCount = 0;
  int refreshSyncCount = 0;
  String? lastBarcode;
  String? lastQuantity;
  String? lastManualAction;
  String? lastSupervisor;
  String? resumeRef;
  String? idempotencyKey;
  int cancelCount = 0;
  String? cancelledSaleRef;
  String? cancelReasonCode;
  String? cancelReasonText;
  String? routedOrderRef;

  void _fail() {
    if (failure != null) throw failure!;
  }

  @override
  Future<PosSaleWorkspace> loadWorkspace() async {
    loadCount++;
    _fail();
    return saleWorkspace();
  }

  @override
  Future<PosSaleWorkspace> scanBarcode(String barcode) {
    scanCount++;
    lastBarcode = barcode;
    _fail();
    return scanCompleter?.future ?? Future.value(saleWorkspace());
  }

  @override
  Future<List<PosProductView>> searchProducts(String keyword) async {
    searchCount++;
    _fail();
    return [product];
  }

  @override
  Future<PosSaleWorkspace> addProduct(String productRef) async {
    _fail();
    return saleWorkspace();
  }

  @override
  Future<PosSaleWorkspace> changeQuantity(
    String lineRef,
    String quantity,
  ) async {
    lastQuantity = quantity;
    _fail();
    return saleWorkspace();
  }

  @override
  Future<PosSaleWorkspace> refreshPromotionQuote() async {
    refreshQuoteCount++;
    _fail();
    return saleWorkspace();
  }

  @override
  Future<PosSaleWorkspace> applyManualAdjustment({
    required String actionCode,
    required String value,
    String? lineRef,
    String? supervisorCredential,
  }) async {
    lastManualAction = actionCode;
    lastSupervisor = supervisorCredential;
    _fail();
    return saleWorkspace();
  }

  @override
  Future<PosSaleWorkspace> holdCurrentSale() async {
    _fail();
    return saleWorkspace(lines: const []);
  }

  @override
  Future<PosSaleWorkspace> cancelCurrentSale({
    required String reasonCode,
    required String reasonText,
  }) async {
    cancelCount++;
    cancelReasonCode = reasonCode;
    cancelReasonText = reasonText;
    _fail();
    return saleWorkspace(lines: const []);
  }

  @override
  Future<PosSaleWorkspace> cancelHeldSale({
    required String saleRef,
    required String reasonCode,
    required String reasonText,
  }) async {
    cancelCount++;
    cancelledSaleRef = saleRef;
    cancelReasonCode = reasonCode;
    cancelReasonText = reasonText;
    _fail();
    return saleWorkspace();
  }

  @override
  Future<PosSaleWorkspace> resumeHeldSale(String saleRef) async {
    resumeRef = saleRef;
    _fail();
    return saleWorkspace();
  }

  @override
  Future<PosCashSettlementView> settleCash({
    required String tenderedAmount,
    required String idempotencyKey,
  }) async {
    this.idempotencyKey = idempotencyKey;
    _fail();
    return settlement;
  }

  @override
  Future<PosPrintPreviewView> previewPrintTask(String orderRef) async {
    _fail();
    return printPreview;
  }

  @override
  Future<PosReprintRequestView> requestReceiptReprint({
    required String orderRef,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async {
    _fail();
    return PosReprintRequestView(
      printRequestRef: '01K2A000000000000000000091',
      orderRef: orderRef,
      reprintNo: 1,
      documentDigest: List.filled(64, 'a').join(),
      executionStatus: 'BLOCKED_EXTERNAL',
      outboxEventRef: '01K2A000000000000000000092',
      duplicate: false,
    );
  }

  @override
  Future<PosOrderDispositionView> routeCompletedSaleToReturn(
    String orderRef,
  ) async {
    routedOrderRef = orderRef;
    _fail();
    return PosOrderDispositionView(
      dispositionRef: '01K2A000000000000000000093',
      orderRef: orderRef,
      dispositionType: 'RETURN_REFUND_REQUIRED',
      fromStatus: 'COMPLETED',
      effectiveStatus: 'COMPLETED',
      requestDigest: List.filled(64, 'b').join(),
      outboxEventRef: '01K2A000000000000000000094',
      duplicate: false,
    );
  }

  @override
  Future<PosSaleWorkspace> refreshSyncStatus() async {
    refreshSyncCount++;
    _fail();
    return saleWorkspace(
      syncStatus: const PosSyncStatusView(
        online: true,
        pendingCount: 0,
        retryCount: 0,
        deadLetterCount: 0,
        lastSuccessfulAt: null,
        safeMessage: '同步正常。',
      ),
    );
  }
}

final class _DeviceGateway implements PosDeviceGateway {
  const _DeviceGateway();

  @override
  Future<DeviceSnapshot> snapshot() async => DeviceSnapshot(
    metadata: const DeviceMetadata(
      manufacturer: 'ACME',
      model: 'POS-01',
      androidRelease: '14',
      androidSdk: 34,
      adapterVersion: '0.1.0',
    ),
    capabilities: {DeviceCapability.receiptPrinter},
  );
}

final class _SessionRepository implements PosSessionRepository {
  const _SessionRepository({required this.permissions});

  final Set<PosPermission> permissions;

  @override
  Future<TrustedTerminalContext> verifyTerminal(DeviceSnapshot device) async =>
      TrustedTerminalContext(
        tenantId: 'TENANT_A',
        tenantName: '虚构便利租户',
        orgUnitId: '101',
        storeId: '1101',
        storeName: '虚构便利一店',
        terminalId: '01K2A000000000000000000011',
        terminalName: '虚构收银机 01',
        storeTimezone: 'Asia/Shanghai',
        businessDate: '2026-08-20',
        status: 'ACTIVE',
        protocolVersion: '1.0',
        validUntil: DateTime.utc(2099),
        approvedCapabilities: {DeviceCapability.receiptPrinter},
      );

  @override
  Future<PosLoginResult> authenticate(
    TrustedTerminalContext terminal,
    EmployeeLoginCommand command,
  ) async => PosLoginResult(
    employee: EmployeeSession(
      employeeId: '101',
      employeeName: '虚构收银员甲',
      sessionRef: 'session:synthetic:0001',
      authenticatedAt: fixtureNow,
      expiresAt: fixtureNow.add(const Duration(hours: 8)),
      roles: const {'CASHIER'},
      permissions: permissions,
    ),
    shift: PosShiftContext(
      shiftId: '01K2A000000000000000000021',
      businessDate: '2026-08-20',
      status: 'OPEN',
      openedAt: fixtureNow,
    ),
  );

  @override
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async => PosSessionRefresh(
    terminal: terminal,
    employee: employee,
    shift: PosShiftContext(
      shiftId: '01K2A000000000000000000021',
      businessDate: '2026-08-20',
      status: 'OPEN',
      openedAt: fixtureNow,
    ),
  );

  @override
  Future<void> logout(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
    String correlationId,
  ) async {}
}
