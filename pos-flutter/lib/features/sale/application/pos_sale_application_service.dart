import '../domain/pos_sale_models.dart';

/// POS-008 正式页面应用端口；具体实现组合既有 Owner，页面不得越过此边界。
abstract interface class PosSaleApplicationService {
  /// 加载与可信终端当前班次绑定的工作区。
  Future<PosSaleWorkspace> loadWorkspace();

  /// 扫码并由商品/价格 Owner 校验后加入购物篮。
  Future<PosSaleWorkspace> scanBarcode(String barcode);

  /// 搜索已安装且当前有效的商品销售投影。
  Future<List<PosProductView>> searchProducts(String keyword);

  /// 使用搜索结果的稳定引用加购，禁止页面提交价格或租户。
  Future<PosSaleWorkspace> addProduct(String productRef);

  /// 数量以规范十进制字符串提交，由 Checkout Owner 校验和报价。
  Future<PosSaleWorkspace> changeQuantity(String lineRef, String quantity);

  /// 使用冻结规则包重新报价，返回新版本及 fingerprint。
  Future<PosSaleWorkspace> refreshPromotionQuote();

  /// 预检并应用受权人工优惠；授权和限额由 Promotion Owner 决定。
  Future<PosSaleWorkspace> applyManualAdjustment({
    required String actionCode,
    required String value,
    String? lineRef,
    String? supervisorCredential,
  });

  /// 挂起当前购物篮并生成新空篮，事实由 Checkout Owner 原子提交。
  Future<PosSaleWorkspace> holdCurrentSale();

  /// 按稳定挂单引用恢复，Owner 必须重新校验班次、状态和版本。
  Future<PosSaleWorkspace> resumeHeldSale(String saleRef);

  /// 现金成交只提交收款字符串与稳定幂等键，不允许页面拼装订单事实。
  Future<PosCashSettlementView> settleCash({
    required String tenderedAmount,
    required String idempotencyKey,
  });

  /// 读取已完成订单的打印任务预览；不得控制真实打印机。
  Future<PosPrintPreviewView> previewPrintTask(String orderRef);

  /// 刷新正式同步应用服务的积压、重试和死信只读状态。
  Future<PosSaleWorkspace> refreshSyncStatus();
}
