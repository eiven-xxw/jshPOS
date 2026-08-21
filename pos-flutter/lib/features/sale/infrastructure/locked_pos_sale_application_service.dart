import '../application/pos_sale_application_service.dart';
import '../domain/pos_sale_models.dart';

/// 未配置 Checkout/Catalog/Promotion/Sync 正式组合根时的默认实现，始终失败关闭。
final class LockedPosSaleApplicationService
    implements PosSaleApplicationService {
  const LockedPosSaleApplicationService();

  Never _unavailable() => throw const PosSaleFailure(
    'POS_WORKSPACE_UNAVAILABLE',
    '收银应用服务尚未完成安全配置，请联系管理员。',
  );

  @override
  Future<PosSaleWorkspace> addProduct(String productRef) async =>
      _unavailable();

  @override
  Future<PosSaleWorkspace> applyManualAdjustment({
    required String actionCode,
    required String value,
    String? lineRef,
    String? supervisorCredential,
  }) async => _unavailable();

  @override
  Future<PosSaleWorkspace> changeQuantity(
    String lineRef,
    String quantity,
  ) async => _unavailable();

  @override
  Future<PosSaleWorkspace> holdCurrentSale() async => _unavailable();

  @override
  Future<PosSaleWorkspace> cancelCurrentSale({
    required String reasonCode,
    required String reasonText,
  }) async => _unavailable();

  @override
  Future<PosSaleWorkspace> cancelHeldSale({
    required String saleRef,
    required String reasonCode,
    required String reasonText,
  }) async => _unavailable();

  @override
  Future<PosSaleWorkspace> loadWorkspace() async => _unavailable();

  @override
  Future<PosPrintPreviewView> previewPrintTask(String orderRef) async =>
      _unavailable();

  @override
  Future<PosReprintRequestView> requestReceiptReprint({
    required String orderRef,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async => _unavailable();

  @override
  Future<PosOrderDispositionView> routeCompletedSaleToReturn(
    String orderRef,
  ) async => _unavailable();

  @override
  Future<PosSaleWorkspace> refreshPromotionQuote() async => _unavailable();

  @override
  Future<PosSaleWorkspace> refreshSyncStatus() async => _unavailable();

  @override
  Future<PosSaleWorkspace> resumeHeldSale(String saleRef) async =>
      _unavailable();

  @override
  Future<PosSaleWorkspace> scanBarcode(String barcode) async => _unavailable();

  @override
  Future<List<PosProductView>> searchProducts(String keyword) async =>
      _unavailable();

  @override
  Future<PosCashSettlementView> settleCash({
    required String tenderedAmount,
    required String idempotencyKey,
  }) async => _unavailable();
}
