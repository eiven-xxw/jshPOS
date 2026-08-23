import '../../session/application/pos_session_service.dart';
import '../../session/domain/pos_session_models.dart';
import '../domain/pos_sale_models.dart';
import 'pos_sale_application_service.dart';

/// POS-008 页面阶段；一次只允许一个会改变购物篮或成交事实的命令执行。
enum PosSalePagePhase { loading, ready, busy, failed, settled }

/// 页面只读状态；错误只保留安全错误码与展示信息。
final class PosSalePageState {
  const PosSalePageState({
    required this.phase,
    this.workspace,
    this.searchResults = const [],
    this.settlement,
    this.printPreview,
    this.reprintRequest,
    this.disposition,
    this.errorCode,
    this.safeMessage,
  });

  const PosSalePageState.loading() : this(phase: PosSalePagePhase.loading);

  final PosSalePagePhase phase;
  final PosSaleWorkspace? workspace;
  final List<PosProductView> searchResults;
  final PosCashSettlementView? settlement;
  final PosPrintPreviewView? printPreview;
  final PosReprintRequestView? reprintRequest;
  final PosOrderDispositionView? disposition;
  final String? errorCode;
  final String? safeMessage;

  bool get busy =>
      phase == PosSalePagePhase.loading || phase == PosSalePagePhase.busy;
}

/// 页面编排器统一执行权限、单航班、错误收敛和应用端口调用。
final class PosSaleController {
  PosSaleController({required this.sessionService, required this.saleService});

  final PosSessionService sessionService;
  final PosSaleApplicationService saleService;
  PosSalePageState _state = const PosSalePageState.loading();
  Future<PosSalePageState>? _flight;

  PosSalePageState get state => _state;

  Future<PosSalePageState> initialize() => _run(
    permission: PosPermission.saleOperate,
    operation: saleService.loadWorkspace,
  );

  Future<PosSalePageState> scan(String barcode) => _run(
    permission: PosPermission.saleOperate,
    operation: () => saleService.scanBarcode(barcode.trim()),
  );

  Future<PosSalePageState> search(String keyword) {
    if (keyword.trim().length < 2) {
      return Future.value(
        _state = _copy(
          phase: PosSalePagePhase.ready,
          searchResults: const [],
          clearError: true,
        ),
      );
    }
    return _flight ??= _search(keyword).whenComplete(() => _flight = null);
  }

  Future<PosSalePageState> _search(String keyword) async {
    try {
      sessionService.requirePermission(PosPermission.saleOperate);
      _state = _copy(phase: PosSalePagePhase.busy, clearError: true);
      final results = await saleService.searchProducts(keyword.trim());
      return _state = _copy(
        phase: PosSalePagePhase.ready,
        searchResults: List.unmodifiable(results),
        clearError: true,
      );
    } on PosSessionFailure catch (error) {
      return _failure(error.code, error.message);
    } on PosSaleFailure catch (error) {
      return _failure(error.code, error.message);
    } catch (_) {
      return _failure('POS_SEARCH_FAILED', '商品搜索失败，请稍后重试。');
    }
  }

  Future<PosSalePageState> addProduct(String productRef) => _run(
    permission: PosPermission.saleOperate,
    operation: () => saleService.addProduct(productRef),
    clearSearch: true,
  );

  Future<PosSalePageState> changeQuantity(String lineRef, String quantity) =>
      _run(
        permission: PosPermission.saleOperate,
        operation: () => saleService.changeQuantity(lineRef, quantity),
      );

  Future<PosSalePageState> refreshQuote() => _run(
    permission: PosPermission.saleOperate,
    operation: saleService.refreshPromotionQuote,
  );

  Future<PosSalePageState> identifyMember(String memberToken) => _run(
    permission: PosPermission.saleOperate,
    operation: () => saleService.identifyMember(memberToken),
  );

  Future<PosSalePageState> clearMember() => _run(
    permission: PosPermission.saleOperate,
    operation: saleService.clearMember,
  );

  Future<PosSalePageState> applyManualAdjustment({
    required String actionCode,
    required String value,
    String? lineRef,
    String? supervisorCredential,
  }) => _run(
    permission: PosPermission.manualDiscount,
    operation: () => saleService.applyManualAdjustment(
      actionCode: actionCode,
      value: value,
      lineRef: lineRef,
      supervisorCredential: supervisorCredential,
    ),
  );

  Future<PosSalePageState> hold() => _run(
    permission: PosPermission.saleOperate,
    operation: saleService.holdCurrentSale,
  );

  Future<PosSalePageState> resume(String saleRef) => _run(
    permission: PosPermission.saleOperate,
    operation: () => saleService.resumeHeldSale(saleRef),
  );

  Future<PosSalePageState> cancelCurrent({
    required String reasonCode,
    required String reasonText,
  }) => _run(
    permission: PosPermission.orderCancel,
    operation: () => saleService.cancelCurrentSale(
      reasonCode: reasonCode,
      reasonText: reasonText,
    ),
  );

  Future<PosSalePageState> cancelHeld({
    required String saleRef,
    required String reasonCode,
    required String reasonText,
  }) => _run(
    permission: PosPermission.orderCancel,
    operation: () => saleService.cancelHeldSale(
      saleRef: saleRef,
      reasonCode: reasonCode,
      reasonText: reasonText,
    ),
  );

  Future<PosSalePageState> refreshSync() => _run(
    permission: PosPermission.syncView,
    operation: saleService.refreshSyncStatus,
  );

  Future<PosSalePageState> settleCash({
    required String tenderedAmount,
    required String idempotencyKey,
  }) => _flight ??= _settleCash(
    tenderedAmount: tenderedAmount,
    idempotencyKey: idempotencyKey,
  ).whenComplete(() => _flight = null);

  Future<PosSalePageState> _settleCash({
    required String tenderedAmount,
    required String idempotencyKey,
  }) async {
    try {
      sessionService.requirePermission(PosPermission.cashSettle);
      _state = _copy(phase: PosSalePagePhase.busy, clearError: true);
      final result = await saleService.settleCash(
        tenderedAmount: tenderedAmount,
        idempotencyKey: idempotencyKey,
      );
      return _state = PosSalePageState(
        phase: PosSalePagePhase.settled,
        workspace: _state.workspace,
        settlement: result,
      );
    } on PosSessionFailure catch (error) {
      return _failure(error.code, error.message);
    } on PosSaleFailure catch (error) {
      return _failure(error.code, error.message);
    } catch (_) {
      return _failure('CASH_SETTLEMENT_FAILED', '现金结算失败，请使用原幂等键重试。');
    }
  }

  Future<PosSalePageState> loadPrintPreview(String orderRef) =>
      _flight ??= _loadPrintPreview(orderRef)
          .whenComplete(() => _flight = null);

  Future<PosSalePageState> _loadPrintPreview(String orderRef) async {
    try {
      sessionService.requirePermission(PosPermission.printPreview);
      _state = _copy(phase: PosSalePagePhase.busy, clearError: true);
      final preview = await saleService.previewPrintTask(orderRef);
      return _state = _copy(
        phase: PosSalePagePhase.settled,
        printPreview: preview,
        clearError: true,
      );
    } on PosSessionFailure catch (error) {
      return _failure(error.code, error.message);
    } on PosSaleFailure catch (error) {
      return _failure(error.code, error.message);
    } catch (_) {
      return _failure('PRINT_PREVIEW_FAILED', '打印任务预览失败，请稍后重试。');
    }
  }

  Future<PosSalePageState> requestReprint({
    required String orderRef,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) => _flight ??= _requestReprint(
    orderRef: orderRef,
    reasonCode: reasonCode,
    reasonText: reasonText,
    idempotencyKey: idempotencyKey,
  ).whenComplete(() => _flight = null);

  Future<PosSalePageState> _requestReprint({
    required String orderRef,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async {
    try {
      sessionService.requirePermission(PosPermission.printReprint);
      _state = _copy(phase: PosSalePagePhase.busy, clearError: true);
      final request = await saleService.requestReceiptReprint(
        orderRef: orderRef,
        reasonCode: reasonCode,
        reasonText: reasonText,
        idempotencyKey: idempotencyKey,
      );
      final preview = await saleService.previewPrintTask(orderRef);
      return _state = PosSalePageState(
        phase: PosSalePagePhase.settled,
        workspace: _state.workspace,
        settlement: _state.settlement,
        printPreview: preview,
        reprintRequest: request,
      );
    } on PosSessionFailure catch (error) {
      return _failure(error.code, error.message);
    } on PosSaleFailure catch (error) {
      return _failure(error.code, error.message);
    } catch (_) {
      return _failure('REPRINT_REQUEST_FAILED', '补打请求失败，请使用原幂等键重试。');
    }
  }

  Future<PosSalePageState> routeCompletedSaleToReturn(String orderRef) =>
      _flight ??= _routeCompletedSaleToReturn(orderRef)
          .whenComplete(() => _flight = null);

  Future<PosSalePageState> _routeCompletedSaleToReturn(String orderRef) async {
    try {
      sessionService.requirePermission(PosPermission.orderDispose);
      _state = _copy(phase: PosSalePagePhase.busy, clearError: true);
      final result = await saleService.routeCompletedSaleToReturn(orderRef);
      return _state = PosSalePageState(
        phase: PosSalePagePhase.settled,
        workspace: _state.workspace,
        settlement: _state.settlement,
        printPreview: _state.printPreview,
        reprintRequest: _state.reprintRequest,
        disposition: result,
      );
    } on PosSessionFailure catch (error) {
      return _failure(error.code, error.message);
    } on PosSaleFailure catch (error) {
      return _failure(error.code, error.message);
    } catch (_) {
      return _failure('ORDER_DISPOSITION_FAILED', '反向处置路由失败，请使用原命令恢复。');
    }
  }

  /// 成交完成后由应用服务创建新篮，页面不复用已冻结的原交易命令。
  Future<PosSalePageState> startNextSale() {
    _state = const PosSalePageState.loading();
    return initialize();
  }

  Future<PosSalePageState> _run({
    required PosPermission permission,
    required Future<PosSaleWorkspace> Function() operation,
    bool clearSearch = false,
  }) {
    return _flight ??= _runWorkspace(
      permission: permission,
      operation: operation,
      clearSearch: clearSearch,
    ).whenComplete(() => _flight = null);
  }

  Future<PosSalePageState> _runWorkspace({
    required PosPermission permission,
    required Future<PosSaleWorkspace> Function() operation,
    required bool clearSearch,
  }) async {
    try {
      sessionService.requirePermission(permission);
      _state = _copy(phase: PosSalePagePhase.busy, clearError: true);
      final workspace = await operation();
      return _state = PosSalePageState(
        phase: PosSalePagePhase.ready,
        workspace: workspace,
        searchResults: clearSearch ? const [] : _state.searchResults,
      );
    } on PosSessionFailure catch (error) {
      return _failure(error.code, error.message);
    } on PosSaleFailure catch (error) {
      return _failure(error.code, error.message);
    } catch (_) {
      return _failure('POS_OPERATION_FAILED', '操作失败，请检查状态后使用原命令重试。');
    }
  }

  PosSalePageState _failure(String code, String message) => _state = _copy(
    phase: PosSalePagePhase.failed,
    errorCode: code,
    safeMessage: message,
  );

  PosSalePageState _copy({
    required PosSalePagePhase phase,
    List<PosProductView>? searchResults,
    PosPrintPreviewView? printPreview,
    PosReprintRequestView? reprintRequest,
    PosOrderDispositionView? disposition,
    String? errorCode,
    String? safeMessage,
    bool clearError = false,
  }) => PosSalePageState(
    phase: phase,
    workspace: _state.workspace,
    searchResults: searchResults ?? _state.searchResults,
    settlement: _state.settlement,
    printPreview: printPreview ?? _state.printPreview,
    reprintRequest: reprintRequest ?? _state.reprintRequest,
    disposition: disposition ?? _state.disposition,
    errorCode: clearError ? null : errorCode ?? _state.errorCode,
    safeMessage: clearError ? null : safeMessage ?? _state.safeMessage,
  );
}
