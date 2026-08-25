import 'package:cryptography/cryptography.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

import '../../features/catalog/infrastructure/catalog_package_installer.dart';
import '../../features/exchange/application/pos_exchange_application_service.dart';
import '../../features/exchange/domain/pos_exchange_models.dart';
import '../../features/exchange/infrastructure/http_pos_exchange_application_service.dart';
import '../../features/checkout/application/checkout_local_service.dart';
import '../../features/checkout/domain/checkout_models.dart';
import '../../features/checkout/domain/ulid_generator.dart';
import '../../features/promotion/application/local_manual_adjustment_service.dart';
import '../../features/promotion/application/local_promotion_quote_service.dart';
import '../../features/promotion/domain/manual_adjustment_engine.dart';
import '../../features/promotion/domain/promotion_engine.dart';
import '../../features/promotion/domain/member_benefit_engine.dart';
import '../../features/promotion/infrastructure/promotion_package_installer.dart';
import '../../features/promotion/infrastructure/member_benefit_package_installer.dart';
import '../../features/return_refund/application/pos_return_application_service.dart';
import '../../features/return_refund/domain/pos_return_models.dart';
import '../../features/return_refund/infrastructure/http_pos_return_application_service.dart';
import '../../features/sale/application/pos_sale_application_service.dart';
import '../../features/sale/domain/pos_sale_models.dart';
import '../../features/sale/infrastructure/local_pos_sale_application_service.dart';
import '../../features/session/application/pos_session_repository.dart';
import '../../features/session/domain/pos_session_models.dart';
import '../../features/shift/application/pos_shift_application_service.dart';
import '../../features/shift/domain/shift_models.dart';
import '../../features/shift/infrastructure/local_pos_shift_application_service.dart';
import '../../features/synchronization/application/sync_coordinator.dart';
import '../../features/synchronization/infrastructure/pos_sync_http_transport.dart';
import '../local_database/pos_local_database.dart';
import '../local_database/member_cache_store.dart';
import 'http_signed_package_source.dart';

/// 登录成功后创建的会话级正式业务运行时；每个 Owner 仍通过既有应用端口协作。
final class PosBusinessRuntime {
  const PosBusinessRuntime({
    required this.database,
    required this.sale,
    required this.returns,
    required this.exchange,
    required this.shift,
  });

  final PosLocalDatabase database;
  final PosSaleApplicationService sale;
  final PosReturnApplicationService returns;
  final PosExchangeApplicationService exchange;
  final PosShiftApplicationService shift;
}

abstract interface class PosBusinessRuntimeAssembler {
  Future<PosBusinessRuntime> assemble(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  );
}

/// 文件 SQLite + 签名数据包 + 正式同步/退货 HTTP 的生产组合器。
final class FilePosBusinessRuntimeAssembler
    implements PosBusinessRuntimeAssembler {
  FilePosBusinessRuntimeAssembler({
    required this.databasePathProvider,
    required this.baseUri,
    required this.clientId,
    required this.accessTokenProvider,
    required this.catalogPackageVersion,
    required this.promotionPackageVersion,
    required this.catalogSigningKeys,
    required this.promotionSigningKeys,
    required this.industryTemplateVersion,
    required this.returnWarehouseId,
    required this.configVersion,
    required this.cashDifferenceApprovalMinor,
    this.memberBenefitEnabled = false,
    this.memberBenefitPackageVersion = 0,
    this.memberBenefitSigningKeys = const {},
    this.memberBenefitCapabilityVersion = 1,
    this.memberBenefitCapabilitySha256,
    HttpSignedPackageSource? packageSource,
  }) : _packageSource =
           packageSource ??
           HttpSignedPackageSource(
             baseUri: baseUri,
             clientId: clientId,
             accessTokenProvider: accessTokenProvider,
           );

  final Future<String> Function(TrustedDeviceBinding binding)
  databasePathProvider;
  final Uri baseUri;
  final String clientId;
  final Future<String> Function() accessTokenProvider;
  final int catalogPackageVersion;
  final int promotionPackageVersion;
  final Map<String, SimplePublicKey> catalogSigningKeys;
  final Map<String, SimplePublicKey> promotionSigningKeys;
  final String industryTemplateVersion;
  final String returnWarehouseId;
  final int configVersion;
  final int cashDifferenceApprovalMinor;
  final bool memberBenefitEnabled;
  final int memberBenefitPackageVersion;
  final Map<String, SimplePublicKey> memberBenefitSigningKeys;
  final int memberBenefitCapabilityVersion;
  final String? memberBenefitCapabilitySha256;
  final HttpSignedPackageSource _packageSource;

  @override
  Future<PosBusinessRuntime> assemble(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async {
    if (catalogPackageVersion <= 0 ||
        promotionPackageVersion <= 0 ||
        configVersion <= 0 ||
        catalogSigningKeys.isEmpty ||
        promotionSigningKeys.isEmpty ||
        (memberBenefitEnabled &&
            (memberBenefitPackageVersion <= 0 ||
                memberBenefitSigningKeys.isEmpty ||
                memberBenefitCapabilityVersion <= 0 ||
                memberBenefitCapabilitySha256 == null ||
                !RegExp(r'^[a-f0-9]{64}$')
                    .hasMatch(memberBenefitCapabilitySha256!))) ||
        !UlidGenerator.isCanonical(returnWarehouseId)) {
      throw const PosSessionFailure(
        'RUNTIME_CONFIGURATION_INVALID',
        'POS 正式运行参数尚未完整配置。',
      );
    }
    final binding = TrustedDeviceBinding(
      tenantId: terminal.tenantId,
      storeId: terminal.storeId,
      deviceId: terminal.deviceId,
      terminalId: terminal.terminalId,
      cashierId: employee.employeeId,
      cashierName: employee.employeeName,
      storeTimezone: terminal.storeTimezone,
    );
    final database = PosLocalDatabase.openPath(
      await databasePathProvider(binding),
      binding,
    );
    try {
      final ulids = UlidGenerator();
      final catalog = CatalogPackageInstaller(
        database,
        trustedSigningKeys: catalogSigningKeys,
      );
      await _ensureCatalog(catalog, binding);
      final promotionPackages = PromotionPackageInstaller(
        database,
        trustedSigningKeys: promotionSigningKeys,
      );
      await _ensurePromotion(promotionPackages, binding);
      MemberBenefitPackageInstaller? memberBenefitPackages;
      if (memberBenefitEnabled) {
        memberBenefitPackages = MemberBenefitPackageInstaller(
          database,
          trustedSigningKeys: memberBenefitSigningKeys,
        );
        await _ensureMemberBenefit(memberBenefitPackages, binding);
      }
      final checkout = CheckoutLocalService(
        localDatabase: database,
        ulids: ulids,
        shiftPolicy: ShiftPolicy(
          cashDifferenceApprovalMinor: cashDifferenceApprovalMinor,
        ),
      );
      final transport = PosSyncHttpTransport(
        baseUri: baseUri.resolve('api/pos/v1/'),
        clientId: clientId,
        deviceId: binding.deviceId,
        accessTokenProvider: accessTokenProvider,
      );
      final sync = PosSyncCoordinator(
        localDatabase: database,
        transport: transport,
        ulids: ulids,
        changeApplier: const SyncControlChangeApplier(),
      );
      final sale = LocalPosSaleApplicationService(
        database: database,
        catalog: catalog,
        promotions: LocalPromotionQuoteService(
          database: database,
          packageInstaller: promotionPackages,
          engine: PromotionEngine(),
          ulids: ulids,
          memberBenefitPackageInstaller: memberBenefitPackages,
          memberBenefitEngine: memberBenefitEnabled
              ? MemberBenefitEngine()
              : null,
        ),
        manualAdjustments: LocalManualAdjustmentService(
          database: database,
          packageInstaller: promotionPackages,
          engine: ManualAdjustmentEngine(),
          approvalPort: const RejectingManualApprovalPort(),
          ulids: ulids,
        ),
        checkout: checkout,
        syncCoordinator: sync,
        ulids: ulids,
        industryTemplateVersion: industryTemplateVersion,
        memberCache: memberBenefitEnabled ? MemberCacheStore(database) : null,
        memberBenefitEnabled: memberBenefitEnabled,
        memberBenefitCapabilityVersion: memberBenefitCapabilityVersion,
        memberBenefitCapabilitySha256: memberBenefitCapabilitySha256,
        permissions: employee.permissions,
        authorizationRef: employee.sessionRef,
      );
      final shift = LocalPosShiftApplicationService(
        database: database,
        checkout: checkout,
        ulids: ulids,
        configVersion: configVersion,
        permissions: employee.permissions,
        authorizationRef: employee.sessionRef,
      );
      final returns = HttpPosReturnApplicationService(
        baseUri: baseUri.resolve('api/v1/'),
        clientId: clientId,
        binding: binding,
        accessTokenProvider: accessTokenProvider,
        currentShiftIdProvider: () => _currentShift(database),
        returnWarehouseIdProvider: () => returnWarehouseId,
        ulids: ulids,
      );
      final exchange = HttpPosExchangeApplicationService(
        baseUri: baseUri.resolve('api/v1/'),
        clientId: clientId,
        binding: binding,
        database: database,
        accessTokenProvider: accessTokenProvider,
        ulids: ulids,
      );
      return PosBusinessRuntime(
        database: database,
        sale: sale,
        returns: returns,
        exchange: exchange,
        shift: shift,
      );
    } catch (_) {
      database.close();
      rethrow;
    }
  }

  Future<void> _ensureCatalog(
    CatalogPackageInstaller installer,
    TrustedDeviceBinding binding,
  ) async {
    final current = installer.database.database.select(
      'SELECT active_package_version FROM local_catalog_package_binding WHERE singleton_id=1 AND tenant_id=? AND store_id=?',
      [binding.tenantId, binding.storeId],
    );
    if (current.isNotEmpty) {
      final version = current.single['active_package_version']! as int;
      if (version == catalogPackageVersion) return;
      if (version >= catalogPackageVersion) {
        throw StateError('CAT-DPK-104: configured catalog version is stale');
      }
    }
    await installer.install(
      await _packageSource.catalog(
        storeId: binding.storeId,
        packageVersion: catalogPackageVersion,
      ),
    );
  }

  Future<void> _ensurePromotion(
    PromotionPackageInstaller installer,
    TrustedDeviceBinding binding,
  ) async {
    final current = installer.database.database.select(
      'SELECT active_package_version FROM local_promotion_package_binding WHERE singleton_id=1 AND tenant_id=? AND store_id=?',
      [binding.tenantId, binding.storeId],
    );
    if (current.isNotEmpty) {
      final version = current.single['active_package_version']! as int;
      if (version == promotionPackageVersion) return;
      if (version >= promotionPackageVersion) {
        throw StateError('PRM-PKG-104: configured promotion version is stale');
      }
    }
    await installer.install(
      await _packageSource.promotion(
        storeId: binding.storeId,
        packageVersion: promotionPackageVersion,
      ),
    );
  }

  Future<void> _ensureMemberBenefit(
    MemberBenefitPackageInstaller installer,
    TrustedDeviceBinding binding,
  ) async {
    final current = installer.database.database.select(
      'SELECT active_package_version FROM local_member_benefit_package_binding WHERE singleton_id=1 AND tenant_id=? AND store_id=?',
      [binding.tenantId, binding.storeId],
    );
    if (current.isNotEmpty) {
      final version = current.single['active_package_version']! as int;
      if (version == memberBenefitPackageVersion) return;
      if (version >= memberBenefitPackageVersion) {
        throw StateError(
          'MBP-PKG-104: configured member benefit version is stale',
        );
      }
    }
    await installer.install(
      await _packageSource.memberBenefit(
        storeId: binding.storeId,
        packageVersion: memberBenefitPackageVersion,
      ),
    );
  }
}

/// 同一对象同时承接会话与页面端口，确保员工登录成功后才建立其 SQLite 绑定。
final class SessionBoundPosRuntime
    implements
        PosSessionRepository,
        PosSaleApplicationService,
        PosReturnApplicationService,
        PosExchangeApplicationService,
        PosShiftApplicationService {
  SessionBoundPosRuntime({required this.sessions, required this.assembler});

  final PosSessionRepository sessions;
  final PosBusinessRuntimeAssembler assembler;
  PosBusinessRuntime? _business;

  @override
  Future<TrustedTerminalContext> verifyTerminal(DeviceSnapshot device) =>
      sessions.verifyTerminal(device);

  @override
  Future<PosLoginResult> authenticate(
    TrustedTerminalContext terminal,
    EmployeeLoginCommand command,
  ) async {
    final result = await sessions.authenticate(terminal, command);
    _disposeBusiness();
    try {
      _business = await assembler.assemble(terminal, result.employee);
    } catch (_) {
      // 服务端认证成功但本地正式运行时无法装配时，主动撤销刚建立的员工会话。
      // 撤销失败不能覆盖原始装配异常，且本地仍保持无业务数据库的失败关闭状态。
      try {
        await sessions.logout(terminal, result.employee, command.correlationId);
      } catch (_) {
        // 登录失败路径不得因为二次清理异常而伪装成可用会话。
      }
      rethrow;
    }
    return PosLoginResult(
      employee: result.employee,
      shift: _currentShiftContext(_business!.database),
    );
  }

  @override
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async {
    final result = await sessions.refresh(terminal, employee);
    return PosSessionRefresh(
      terminal: result.terminal,
      employee: result.employee,
      shift: _business == null
          ? null
          : _currentShiftContext(_business!.database),
    );
  }

  @override
  Future<void> logout(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
    String correlationId,
  ) async {
    await sessions.logout(terminal, employee, correlationId);
    _disposeBusiness();
  }

  PosBusinessRuntime get _ready {
    final runtime = _business;
    if (runtime == null) {
      throw const PosSessionFailure('SESSION_REQUIRED', '请先完成员工登录。');
    }
    return runtime;
  }

  @override
  Future<PosSaleWorkspace> loadWorkspace() => _ready.sale.loadWorkspace();
  @override
  Future<PosSaleWorkspace> scanBarcode(String barcode) =>
      _ready.sale.scanBarcode(barcode);
  @override
  Future<List<PosProductView>> searchProducts(String keyword) =>
      _ready.sale.searchProducts(keyword);
  @override
  Future<PosSaleWorkspace> addProduct(String productRef) =>
      _ready.sale.addProduct(productRef);
  @override
  Future<PosSaleWorkspace> changeQuantity(String lineRef, String quantity) =>
      _ready.sale.changeQuantity(lineRef, quantity);
  @override
  Future<PosSaleWorkspace> refreshPromotionQuote() =>
      _ready.sale.refreshPromotionQuote();
  @override
  Future<PosSaleWorkspace> identifyMember(String memberToken) =>
      _ready.sale.identifyMember(memberToken);
  @override
  Future<PosSaleWorkspace> clearMember() => _ready.sale.clearMember();
  @override
  Future<PosSaleWorkspace> applyManualAdjustment({
    required String actionCode,
    required String value,
    String? lineRef,
    String? supervisorCredential,
  }) => _ready.sale.applyManualAdjustment(
    actionCode: actionCode,
    value: value,
    lineRef: lineRef,
    supervisorCredential: supervisorCredential,
  );
  @override
  Future<PosSaleWorkspace> holdCurrentSale() => _ready.sale.holdCurrentSale();
  @override
  Future<PosSaleWorkspace> resumeHeldSale(String saleRef) =>
      _ready.sale.resumeHeldSale(saleRef);
  @override
  Future<PosSaleWorkspace> cancelCurrentSale({
    required String reasonCode,
    required String reasonText,
  }) => _ready.sale.cancelCurrentSale(
    reasonCode: reasonCode,
    reasonText: reasonText,
  );
  @override
  Future<PosSaleWorkspace> cancelHeldSale({
    required String saleRef,
    required String reasonCode,
    required String reasonText,
  }) => _ready.sale.cancelHeldSale(
    saleRef: saleRef,
    reasonCode: reasonCode,
    reasonText: reasonText,
  );
  @override
  Future<PosCashSettlementView> settleCash({
    required String tenderedAmount,
    required String idempotencyKey,
  }) => _ready.sale.settleCash(
    tenderedAmount: tenderedAmount,
    idempotencyKey: idempotencyKey,
  );
  @override
  Future<PosPrintPreviewView> previewPrintTask(String orderRef) =>
      _ready.sale.previewPrintTask(orderRef);
  @override
  Future<PosReprintRequestView> requestReceiptReprint({
    required String orderRef,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) => _ready.sale.requestReceiptReprint(
    orderRef: orderRef,
    reasonCode: reasonCode,
    reasonText: reasonText,
    idempotencyKey: idempotencyKey,
  );
  @override
  Future<PosOrderDispositionView> routeCompletedSaleToReturn(String orderRef) =>
      _ready.sale.routeCompletedSaleToReturn(orderRef);
  @override
  Future<PosSaleWorkspace> refreshSyncStatus() =>
      _ready.sale.refreshSyncStatus();
  @override
  Future<PosReturnWorkspace> findOriginalOrder(String orderQuery) =>
      _ready.returns.findOriginalOrder(orderQuery);
  @override
  Future<PosReturnWorkspace> changeRequestedQuantity(
    String orderLineRef,
    String quantity,
  ) => _ready.returns.changeRequestedQuantity(orderLineRef, quantity);
  @override
  Future<PosReturnSubmissionView> submitCashReturn({
    required String reasonCode,
    String? supervisorCredential,
  }) => _ready.returns.submitCashReturn(
    reasonCode: reasonCode,
    supervisorCredential: supervisorCredential,
  );
  @override
  Future<PosReturnSubmissionView> refreshReturnStatus(String returnRef) =>
      _ready.returns.refreshReturnStatus(returnRef);
  @override
  Future<PosExchangeView> create({
    required PosExchangeSource source,
    required String reasonCode,
  }) => _ready.exchange.create(source: source, reasonCode: reasonCode);
  @override
  Future<PosExchangeView> refreshExchange(String exchangeRef) =>
      _ready.exchange.refreshExchange(exchangeRef);
  @override
  Future<PosExchangeView> approve({
    required String exchangeRef,
    required String correlationRef,
    required String reasonCode,
  }) => _ready.exchange.approve(
    exchangeRef: exchangeRef,
    correlationRef: correlationRef,
    reasonCode: reasonCode,
  );
  @override
  Future<PosExchangeView> recover({
    required String exchangeRef,
    required String correlationRef,
    required String targetLeg,
    required String reasonCode,
  }) => _ready.exchange.recover(
    exchangeRef: exchangeRef,
    correlationRef: correlationRef,
    targetLeg: targetLeg,
    reasonCode: reasonCode,
  );
  @override
  Future<PosShiftContext> open({
    required String businessDate,
    required String openingCash,
    required String idempotencyKey,
  }) => _ready.shift.open(
    businessDate: businessDate,
    openingCash: openingCash,
    idempotencyKey: idempotencyKey,
  );
  @override
  Future<void> close({
    required String shiftId,
    required String actualCash,
    required String idempotencyKey,
  }) => _ready.shift.close(
    shiftId: shiftId,
    actualCash: actualCash,
    idempotencyKey: idempotencyKey,
  );

  @override
  Future<ShiftOperationResult> recordCashMovement({
    required String shiftId,
    required ShiftCashMovementType movementType,
    required String amount,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) => _ready.shift.recordCashMovement(
    shiftId: shiftId,
    movementType: movementType,
    amount: amount,
    reasonCode: reasonCode,
    reasonText: reasonText,
    idempotencyKey: idempotencyKey,
  );

  @override
  Future<ShiftOperationResult> requestNoSaleDrawer({
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) => _ready.shift.requestNoSaleDrawer(
    shiftId: shiftId,
    reasonCode: reasonCode,
    reasonText: reasonText,
    idempotencyKey: idempotencyKey,
  );

  void _disposeBusiness() {
    _business?.database.close();
    _business = null;
  }
}

String _currentShift(PosLocalDatabase database) {
  final shift = _currentShiftContext(database);
  if (shift == null) {
    throw const PosSessionFailure('SHIFT_NOT_OPEN', '当前没有可用于退货的进行中班次。');
  }
  return shift.shiftId;
}

PosShiftContext? _currentShiftContext(PosLocalDatabase database) {
  final binding = database.binding;
  final rows = database.database.select(
    '''SELECT shift_id,business_date,status,opened_at FROM local_shift
       WHERE tenant_id=? AND store_id=? AND terminal_id=? AND cashier_id=? AND status='OPEN'
       ORDER BY opened_at DESC LIMIT 2''',
    [binding.tenantId, binding.storeId, binding.terminalId, binding.cashierId],
  );
  if (rows.isEmpty) return null;
  if (rows.length != 1) {
    throw const PosSessionFailure(
      'SHIFT_CONTEXT_INVALID',
      '存在多个进行中班次，终端已失败关闭。',
    );
  }
  final row = rows.single;
  return PosShiftContext(
    shiftId: row['shift_id']! as String,
    businessDate: row['business_date']! as String,
    status: row['status']! as String,
    openedAt: DateTime.parse(row['opened_at']! as String).toUtc(),
  );
}
