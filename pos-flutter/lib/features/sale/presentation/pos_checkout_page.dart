import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../application/pos_sale_controller.dart';
import '../domain/pos_sale_models.dart';

/// POS-008 正式收银页面；只渲染应用服务投影并发送用户意图。
final class PosCheckoutPage extends StatefulWidget {
  const PosCheckoutPage({required this.controller, this.onExchangeSettlement, super.key});

  final PosSaleController controller;
  final Future<void> Function(PosCashSettlementView result)? onExchangeSettlement;

  @override
  State<PosCheckoutPage> createState() => _PosCheckoutPageState();
}

class _PosCheckoutPageState extends State<PosCheckoutPage> {
  final _scannerController = TextEditingController();
  final _searchController = TextEditingController();
  final _scannerFocus = FocusNode(debugLabel: 'barcode-scanner');
  final _searchFocus = FocusNode(debugLabel: 'product-search');

  PosSalePageState get _state => widget.controller.state;

  @override
  void initState() {
    super.initState();
    _execute(
      widget.controller.initialize,
      restoreScannerFocus: true,
      allowWhenLoading: true,
    );
  }

  @override
  void dispose() {
    _scannerController.dispose();
    _searchController.dispose();
    _scannerFocus.dispose();
    _searchFocus.dispose();
    super.dispose();
  }

  Future<void> _execute(
    Future<PosSalePageState> Function() operation, {
    bool restoreScannerFocus = false,
    bool allowWhenLoading = false,
  }) async {
    if (_state.busy && !allowWhenLoading) return;
    await operation();
    if (!mounted) return;
    setState(() {});
    if (restoreScannerFocus) _scannerFocus.requestFocus();
  }

  Future<void> _scan() async {
    final barcode = _scannerController.text.trim();
    if (barcode.isEmpty) return;
    _scannerController.clear();
    await _execute(
      () => widget.controller.scan(barcode),
      restoreScannerFocus: true,
    );
  }

  Future<void> _search() =>
      _execute(() => widget.controller.search(_searchController.text));

  Future<void> _openManualAdjustment() async {
    final input = await showDialog<_ManualAdjustmentInput>(
      context: context,
      builder: (_) => const _ManualAdjustmentDialog(),
    );
    if (input == null) return;
    await _execute(
      () => widget.controller.applyManualAdjustment(
        actionCode: input.actionCode,
        value: input.value,
        supervisorCredential: input.supervisorCredential,
      ),
      restoreScannerFocus: true,
    );
  }

  Future<void> _openCashSettlement() async {
    final workspace = _state.workspace;
    if (workspace == null || !workspace.canSettle) return;
    final tendered = await showDialog<String>(
      context: context,
      builder: (_) => _CashSettlementDialog(
        receivableAmountMinor: workspace.totals.receivableAmountMinor,
      ),
    );
    if (tendered == null) return;
    final idempotencyKey =
        'cash:${workspace.saleRef}:${workspace.quoteFingerprint}';
    await _execute(
      () => widget.controller.settleCash(
        tenderedAmount: tendered,
        idempotencyKey: idempotencyKey,
      ),
    );
    if (!mounted || _state.settlement == null) return;
    await _showSettlementResult(_state.settlement!);
  }

  Future<void> _openCancellation({String? heldSaleRef}) async {
    final input = await showDialog<_OrderCancelInput>(
      context: context,
      builder: (_) => _OrderCancelDialog(heldSale: heldSaleRef != null),
    );
    if (input == null) return;
    await _execute(
      () => heldSaleRef == null
          ? widget.controller.cancelCurrent(
              reasonCode: input.reasonCode,
              reasonText: input.reasonText,
            )
          : widget.controller.cancelHeld(
              saleRef: heldSaleRef,
              reasonCode: input.reasonCode,
              reasonText: input.reasonText,
            ),
      restoreScannerFocus: true,
    );
  }

  Future<void> _showSettlementResult(PosCashSettlementView result) async {
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.check_circle, color: Colors.green),
            SizedBox(width: 10),
            Text('现金收款成功'),
          ],
        ),
        content: SizedBox(
          width: 480,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('订单号：${result.localOrderNo}'),
              Text('应收：${_money(result.receivableAmountMinor)}'),
              Text('实收：${_money(result.tenderedAmountMinor)}'),
              Text('找零：${_money(result.changeAmountMinor)}'),
              const SizedBox(height: 12),
              const Text('订单与 Outbox 已原子提交；同步未知时不得重新生成订单。'),
            ],
          ),
        ),
        actions: [
          if (widget.onExchangeSettlement != null)
            TextButton.icon(
              key: const Key('completeExchangeLink'),
              onPressed: () async {
                Navigator.pop(dialogContext);
                await widget.onExchangeSettlement!(result);
              },
              icon: const Icon(Icons.swap_horiz),
              label: const Text('完成换货关联'),
            ),
          TextButton.icon(
            key: const Key('routeCompletedReturn'),
            onPressed: () async {
              await widget.controller.routeCompletedSaleToReturn(
                result.orderRef,
              );
              if (!dialogContext.mounted) return;
              Navigator.pop(dialogContext);
              if (!mounted) return;
              setState(() {});
              final disposition = _state.disposition;
              if (disposition != null) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('已登记原单退货退款入口；成交状态与历史事实保持不变。')),
                );
              }
            },
            icon: const Icon(Icons.assignment_return_outlined),
            label: const Text('原单退货'),
          ),
          TextButton.icon(
            key: const Key('previewPrintTask'),
            onPressed: () async {
              await widget.controller.loadPrintPreview(result.orderRef);
              if (!dialogContext.mounted) return;
              Navigator.pop(dialogContext);
              if (mounted) setState(() {});
              await _showPrintPreview();
            },
            icon: const Icon(Icons.receipt_long),
            label: const Text('打印预览'),
          ),
          FilledButton(
            key: const Key('startNextSale'),
            onPressed: () async {
              Navigator.pop(dialogContext);
              await _execute(
                widget.controller.startNextSale,
                restoreScannerFocus: true,
              );
            },
            child: const Text('下一单'),
          ),
        ],
      ),
    );
  }

  Future<void> _showPrintPreview() async {
    final preview = _state.printPreview;
    if (preview == null || !mounted) return;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(preview.title),
        content: SizedBox(
          width: 460,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ...preview.lines.map(Text.new),
              const Divider(),
              Text(preview.totalText),
              Text('模板：${preview.templateVersion}'),
              Text('内容摘要：${preview.contentSha256}'),
              if (preview.reprintAuditText != null)
                Text('补打审计：${preview.reprintAuditText}'),
              const SizedBox(height: 12),
              Text('设备证据：${preview.adapterEvidence}（仅预览，未发送真实打印命令）'),
            ],
          ),
        ),
        actions: [
          OutlinedButton.icon(
            key: const Key('requestReceiptReprint'),
            onPressed: () async {
              Navigator.pop(dialogContext);
              await _showReprintDialog(preview.orderRef);
            },
            icon: const Icon(Icons.print),
            label: const Text('申请补打'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  Future<void> _showReprintDialog(String orderRef) async {
    final reason = TextEditingController();
    final idempotencyKey =
        'reprint:$orderRef:${DateTime.now().toUtc().microsecondsSinceEpoch}';
    try {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('补打小票确认'),
          content: SizedBox(
            width: 420,
            child: TextField(
              key: const Key('reprintReason'),
              controller: reason,
              maxLength: 256,
              decoration: const InputDecoration(
                labelText: '补打原因',
                hintText: '例如：顾客遗失后申请副本',
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('取消'),
            ),
            FilledButton(
              key: const Key('confirmReceiptReprint'),
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('登记补打'),
            ),
          ],
        ),
      );
      if (confirmed != true || !mounted) return;
      await widget.controller.requestReprint(
        orderRef: orderRef,
        reasonCode: 'CUSTOMER_COPY',
        reasonText: reason.text,
        idempotencyKey: idempotencyKey,
      );
      if (!mounted) return;
      setState(() {});
      final request = _state.reprintRequest;
      if (request != null) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '补打 #${request.reprintNo} 已登记；真实打印未开放（${request.executionStatus}）',
            ),
          ),
        );
      }
      await _showPrintPreview();
    } finally {
      reason.dispose();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Shortcuts(
      shortcuts: const {
        SingleActivator(LogicalKeyboardKey.f2): _FocusScannerIntent(),
        SingleActivator(LogicalKeyboardKey.f4): _HoldSaleIntent(),
        SingleActivator(LogicalKeyboardKey.f9): _SettleCashIntent(),
      },
      child: Actions(
        actions: {
          _FocusScannerIntent: CallbackAction<_FocusScannerIntent>(
            onInvoke: (_) => _scannerFocus.requestFocus(),
          ),
          _HoldSaleIntent: CallbackAction<_HoldSaleIntent>(
            onInvoke: (_) =>
                _execute(widget.controller.hold, restoreScannerFocus: true),
          ),
          _SettleCashIntent: CallbackAction<_SettleCashIntent>(
            onInvoke: (_) => _openCashSettlement(),
          ),
        },
        child: Focus(
          autofocus: true,
          child: Scaffold(
            appBar: AppBar(
              title: const Text('收银工作台'),
              actions: [
                IconButton(
                  key: const Key('refreshSyncStatus'),
                  tooltip: '刷新同步状态',
                  onPressed: _state.busy
                      ? null
                      : () => _execute(widget.controller.refreshSync),
                  icon: const Icon(Icons.sync),
                ),
                const SizedBox(width: 8),
              ],
            ),
            body: SafeArea(child: _body()),
          ),
        ),
      ),
    );
  }

  Widget _body() {
    if (_state.phase == PosSalePagePhase.loading && _state.workspace == null) {
      return const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('正在恢复购物篮与同步状态…'),
          ],
        ),
      );
    }
    final workspace = _state.workspace;
    if (workspace == null) {
      return _FailurePanel(
        code: _state.errorCode ?? 'POS_WORKSPACE_UNAVAILABLE',
        message: _state.safeMessage ?? '收银工作区不可用。',
        onRetry: () => _execute(widget.controller.initialize),
      );
    }
    return LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 980;
        final basket = _basketPanel(workspace);
        final operation = _operationPanel(workspace);
        return Column(
          children: [
            _SyncBanner(
              status: workspace.syncStatus,
              businessDate: workspace.businessDate,
            ),
            if (_state.safeMessage != null)
              _InlineFailure(
                code: _state.errorCode ?? 'POS_OPERATION_FAILED',
                message: _state.safeMessage!,
              ),
            Expanded(
              child: wide
                  ? Row(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Expanded(flex: 6, child: basket),
                        const VerticalDivider(width: 1),
                        SizedBox(width: 390, child: operation),
                      ],
                    )
                  : ListView(
                      padding: const EdgeInsets.only(bottom: 24),
                      children: [
                        SizedBox(height: 500, child: basket),
                        operation,
                      ],
                    ),
            ),
          ],
        );
      },
    );
  }

  Widget _basketPanel(PosSaleWorkspace workspace) => Column(
    children: [
      Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            Expanded(
              child: TextField(
                key: const Key('barcodeInput'),
                controller: _scannerController,
                focusNode: _scannerFocus,
                enabled: !_state.busy,
                autofocus: true,
                textInputAction: TextInputAction.done,
                onSubmitted: (_) => _scan(),
                decoration: const InputDecoration(
                  labelText: '扫码或输入条码（F2）',
                  prefixIcon: Icon(Icons.qr_code_scanner),
                  border: OutlineInputBorder(),
                ),
              ),
            ),
            const SizedBox(width: 10),
            SizedBox(
              height: 56,
              child: FilledButton.icon(
                key: const Key('scanSubmit'),
                onPressed: _state.busy ? null : _scan,
                icon: const Icon(Icons.add_shopping_cart),
                label: const Text('加购'),
              ),
            ),
          ],
        ),
      ),
      Expanded(
        child: workspace.lines.isEmpty
            ? const Center(child: Text('请扫描商品或从搜索结果加购'))
            : ListView.separated(
                key: const Key('basketLines'),
                padding: const EdgeInsets.symmetric(horizontal: 12),
                itemCount: workspace.lines.length,
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, index) {
                  final line = workspace.lines[index];
                  return _BasketLineTile(
                    line: line,
                    busy: _state.busy,
                    onQuantity: (quantity) => _execute(
                      () => widget.controller.changeQuantity(
                        line.lineRef,
                        quantity,
                      ),
                      restoreScannerFocus: true,
                    ),
                  );
                },
              ),
      ),
    ],
  );

  Widget _operationPanel(PosSaleWorkspace workspace) => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      Text('商品搜索', style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      TextField(
        key: const Key('productSearchInput'),
        controller: _searchController,
        focusNode: _searchFocus,
        enabled: !_state.busy,
        onSubmitted: (_) => _search(),
        decoration: InputDecoration(
          hintText: '名称 / SKU / 条码',
          border: const OutlineInputBorder(),
          suffixIcon: IconButton(
            key: const Key('productSearchSubmit'),
            onPressed: _state.busy ? null : _search,
            icon: const Icon(Icons.search),
          ),
        ),
      ),
      if (_state.searchResults.isNotEmpty) ...[
        const SizedBox(height: 8),
        ..._state.searchResults.map(
          (product) => ListTile(
            key: Key('searchResult:${product.productRef}'),
            contentPadding: EdgeInsets.zero,
            title: Text(product.name),
            subtitle: Text('${product.skuCode} · ${product.unitName}'),
            trailing: FilledButton.tonal(
              onPressed: _state.busy
                  ? null
                  : () => _execute(
                      () => widget.controller.addProduct(product.productRef),
                      restoreScannerFocus: true,
                    ),
              child: Text(_money(product.unitPriceMinor)),
            ),
          ),
        ),
      ],
      const Divider(height: 28),
      _AmountRow(
        label: '商品原价',
        value: _money(workspace.totals.grossAmountMinor),
      ),
      _AmountRow(
        label: '优惠',
        value: '-${_money(workspace.totals.discountAmountMinor)}',
        color: Colors.green.shade700,
      ),
      if (workspace.totals.surchargeAmountMinor > 0)
        _AmountRow(
          label: '附加费',
          value: _money(workspace.totals.surchargeAmountMinor),
        ),
      const Divider(),
      _AmountRow(
        label: '应收',
        value: _money(workspace.totals.receivableAmountMinor),
        prominent: true,
      ),
      const SizedBox(height: 6),
      Text(
        '报价版本 ${workspace.quoteVersion} · ${_shortDigest(workspace.quoteFingerprint)}',
        textAlign: TextAlign.right,
        style: Theme.of(context).textTheme.bodySmall,
      ),
      if (workspace.manualAuthorizationRef != null)
        Text(
          '人工优惠审计：${workspace.manualAuthorizationRef}',
          textAlign: TextAlign.right,
          style: Theme.of(context).textTheme.bodySmall,
        ),
      const SizedBox(height: 16),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          _ActionButton(
            key: const Key('refreshPromotionQuote'),
            icon: Icons.local_offer_outlined,
            label: '重新报价',
            onPressed: _state.busy
                ? null
                : () => _execute(widget.controller.refreshQuote),
          ),
          _ActionButton(
            key: const Key('manualAdjustment'),
            icon: Icons.price_change_outlined,
            label: '人工优惠',
            onPressed: _state.busy ? null : _openManualAdjustment,
          ),
          _ActionButton(
            key: const Key('holdSale'),
            icon: Icons.pause_circle_outline,
            label: '挂单 F4',
            onPressed: _state.busy || workspace.lines.isEmpty
                ? null
                : () => _execute(
                    widget.controller.hold,
                    restoreScannerFocus: true,
                  ),
          ),
          _ActionButton(
            key: const Key('cancelCurrentSale'),
            icon: Icons.cancel_outlined,
            label: '取消本单',
            onPressed: _state.busy || workspace.lines.isEmpty
                ? null
                : _openCancellation,
          ),
        ],
      ),
      if (workspace.heldSales.isNotEmpty) ...[
        const SizedBox(height: 16),
        Text('待取挂单', style: Theme.of(context).textTheme.titleSmall),
        ...workspace.heldSales.map(
          (held) => ListTile(
            key: Key('heldSale:${held.saleRef}'),
            contentPadding: EdgeInsets.zero,
            title: Text(held.localSaleNo),
            subtitle: Text(
              '${held.lineCount} 行 · ${_money(held.receivableAmountMinor)}',
            ),
            trailing: Wrap(
              spacing: 8,
              children: [
                OutlinedButton(
                  key: Key('cancelHeld:${held.saleRef}'),
                  onPressed: _state.busy
                      ? null
                      : () => _openCancellation(heldSaleRef: held.saleRef),
                  child: const Text('取消'),
                ),
                FilledButton.tonal(
                  onPressed: _state.busy
                      ? null
                      : () => _execute(
                          () => widget.controller.resume(held.saleRef),
                          restoreScannerFocus: true,
                        ),
                  child: const Text('取单'),
                ),
              ],
            ),
          ),
        ),
      ],
      const SizedBox(height: 20),
      SizedBox(
        height: 64,
        child: FilledButton.icon(
          key: const Key('cashSettlement'),
          onPressed: _state.busy || !workspace.canSettle
              ? null
              : _openCashSettlement,
          icon: const Icon(Icons.payments_outlined, size: 28),
          label: const Text('现金结算 F9', style: TextStyle(fontSize: 18)),
        ),
      ),
      const SizedBox(height: 12),
      const Text('仅现金内部验证；真实支付、真实打印及真实设备命令未开放。', textAlign: TextAlign.center),
    ],
  );
}

class _BasketLineTile extends StatelessWidget {
  const _BasketLineTile({
    required this.line,
    required this.busy,
    required this.onQuantity,
  });

  final PosBasketLineView line;
  final bool busy;
  final ValueChanged<String> onQuantity;

  @override
  Widget build(BuildContext context) => ListTile(
    key: Key('basketLine:${line.lineRef}'),
    minVerticalPadding: 14,
    title: Text(line.name),
    subtitle: Text(
      '${line.barcode ?? line.productRef} · ${_money(line.unitPriceMinor)}/${line.unitName}'
      '${line.discountAmountMinor > 0 ? ' · 优惠 ${_money(line.discountAmountMinor)}' : ''}',
    ),
    trailing: SizedBox(
      width: 252,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          IconButton.filledTonal(
            key: Key('decrease:${line.lineRef}'),
            tooltip: '减少数量',
            onPressed: busy ? null : () => onQuantity('-1'),
            icon: const Icon(Icons.remove),
          ),
          SizedBox(
            width: 56,
            child: Text(line.quantity, textAlign: TextAlign.center),
          ),
          IconButton.filledTonal(
            key: Key('increase:${line.lineRef}'),
            tooltip: '增加数量',
            onPressed: busy ? null : () => onQuantity('+1'),
            icon: const Icon(Icons.add),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 78,
            child: Text(
              _money(line.receivableAmountMinor),
              textAlign: TextAlign.right,
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
        ],
      ),
    ),
  );
}

class _SyncBanner extends StatelessWidget {
  const _SyncBanner({required this.status, required this.businessDate});

  final PosSyncStatusView status;
  final String businessDate;

  @override
  Widget build(BuildContext context) {
    final warning = !status.online || status.backlogCount > 0;
    return Semantics(
      liveRegion: true,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        color: warning
            ? Theme.of(context).colorScheme.errorContainer
            : Theme.of(context).colorScheme.primaryContainer,
        child: Row(
          children: [
            Icon(status.online ? Icons.cloud_done : Icons.cloud_off),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                '业务日 $businessDate · ${status.safeMessage} · '
                '待同步 ${status.pendingCount} / 重试 ${status.retryCount} / '
                '隔离 ${status.deadLetterCount}',
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _InlineFailure extends StatelessWidget {
  const _InlineFailure({required this.code, required this.message});

  final String code;
  final String message;

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(12),
    color: Theme.of(context).colorScheme.errorContainer,
    child: Text('$message（错误码：$code）'),
  );
}

class _FailurePanel extends StatelessWidget {
  const _FailurePanel({
    required this.code,
    required this.message,
    required this.onRetry,
  });

  final String code;
  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.error_outline, size: 64),
          const SizedBox(height: 12),
          Text(message, textAlign: TextAlign.center),
          Text('错误码：$code'),
          const SizedBox(height: 16),
          SizedBox(
            height: 52,
            child: FilledButton.icon(
              key: const Key('retrySaleWorkspace'),
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: const Text('重试'),
            ),
          ),
        ],
      ),
    ),
  );
}

class _AmountRow extends StatelessWidget {
  const _AmountRow({
    required this.label,
    required this.value,
    this.prominent = false,
    this.color,
  });

  final String label;
  final String value;
  final bool prominent;
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final style = prominent
        ? Theme.of(context).textTheme.headlineSmall
        : Theme.of(context).textTheme.bodyLarge;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        children: [
          Expanded(child: Text(label, style: style)),
          Text(value, style: style?.copyWith(color: color)),
        ],
      ),
    );
  }
}

class _ActionButton extends StatelessWidget {
  const _ActionButton({
    required super.key,
    required this.icon,
    required this.label,
    required this.onPressed,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 52,
    child: FilledButton.tonalIcon(
      onPressed: onPressed,
      icon: Icon(icon),
      label: Text(label),
    ),
  );
}

class _ManualAdjustmentInput {
  const _ManualAdjustmentInput({
    required this.actionCode,
    required this.value,
    this.supervisorCredential,
  });

  final String actionCode;
  final String value;
  final String? supervisorCredential;
}

class _ManualAdjustmentDialog extends StatefulWidget {
  const _ManualAdjustmentDialog();

  @override
  State<_ManualAdjustmentDialog> createState() =>
      _ManualAdjustmentDialogState();
}

class _ManualAdjustmentDialogState extends State<_ManualAdjustmentDialog> {
  final _value = TextEditingController();
  final _supervisor = TextEditingController();
  String _action = 'ORDER_AMOUNT_OFF';

  @override
  void dispose() {
    _value.dispose();
    _supervisor.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('受权人工优惠'),
    content: SizedBox(
      width: 440,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          DropdownButtonFormField<String>(
            initialValue: _action,
            decoration: const InputDecoration(
              labelText: '优惠方式',
              border: OutlineInputBorder(),
            ),
            items: const [
              DropdownMenuItem(value: 'ORDER_AMOUNT_OFF', child: Text('整单减额')),
              DropdownMenuItem(value: 'ORDER_PERCENT_OFF', child: Text('整单折扣')),
              DropdownMenuItem(value: 'ROUNDING', child: Text('现金抹零')),
            ],
            onChanged: (value) => setState(() => _action = value!),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const Key('manualAdjustmentValue'),
            controller: _value,
            autofocus: true,
            decoration: const InputDecoration(
              labelText: '金额 / 折扣率 / 抹零单位',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const Key('supervisorCredential'),
            controller: _supervisor,
            obscureText: true,
            decoration: const InputDecoration(
              labelText: '主管授权（达到阈值时必填）',
              border: OutlineInputBorder(),
            ),
          ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        key: const Key('manualAdjustmentSubmit'),
        onPressed: () => Navigator.pop(
          context,
          _ManualAdjustmentInput(
            actionCode: _action,
            value: _value.text,
            supervisorCredential: _supervisor.text.isEmpty
                ? null
                : _supervisor.text,
          ),
        ),
        child: const Text('提交预检'),
      ),
    ],
  );
}

class _CashSettlementDialog extends StatefulWidget {
  const _CashSettlementDialog({required this.receivableAmountMinor});

  final int receivableAmountMinor;

  @override
  State<_CashSettlementDialog> createState() => _CashSettlementDialogState();
}

class _CashSettlementDialogState extends State<_CashSettlementDialog> {
  final _tendered = TextEditingController();

  @override
  void dispose() {
    _tendered.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('现金结算'),
    content: SizedBox(
      width: 420,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            '应收 ${_money(widget.receivableAmountMinor)}',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 16),
          TextField(
            key: const Key('cashTenderedInput'),
            controller: _tendered,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _submit(),
            decoration: const InputDecoration(
              labelText: '实收金额（元）',
              prefixText: '¥ ',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          const Text('成交由应用服务使用稳定幂等键原子提交；失败后请勿另建订单。'),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        key: const Key('cashTenderedSubmit'),
        onPressed: _submit,
        child: const Text('确认收款'),
      ),
    ],
  );

  void _submit() {
    if (_tendered.text.trim().isEmpty) return;
    Navigator.pop(context, _tendered.text.trim());
  }
}

final class _OrderCancelInput {
  const _OrderCancelInput(this.reasonCode, this.reasonText);

  final String reasonCode;
  final String reasonText;
}

/// 取消必须二次确认并填写原因；页面只提交意图，不直接改订单状态。
final class _OrderCancelDialog extends StatefulWidget {
  const _OrderCancelDialog({required this.heldSale});

  final bool heldSale;

  @override
  State<_OrderCancelDialog> createState() => _OrderCancelDialogState();
}

final class _OrderCancelDialogState extends State<_OrderCancelDialog> {
  final _reason = TextEditingController();
  String _reasonCode = 'CUSTOMER_CANCELLED';

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.heldSale ? '取消挂单' : '取消当前交易'),
    content: SizedBox(
      width: 440,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          DropdownButtonFormField<String>(
            initialValue: _reasonCode,
            decoration: const InputDecoration(
              labelText: '原因类型',
              border: OutlineInputBorder(),
            ),
            items: const [
              DropdownMenuItem(
                value: 'CUSTOMER_CANCELLED',
                child: Text('顾客取消'),
              ),
              DropdownMenuItem(value: 'ENTRY_ERROR', child: Text('录入有误')),
              DropdownMenuItem(
                value: 'HELD_ORDER_ABANDONED',
                child: Text('挂单放弃'),
              ),
            ],
            onChanged: (value) => setState(() => _reasonCode = value!),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const Key('orderCancelReason'),
            controller: _reason,
            maxLength: 256,
            autofocus: true,
            onChanged: (_) => setState(() {}),
            decoration: const InputDecoration(
              labelText: '取消说明（必填）',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 8),
          const Text('取消只适用于未完成交易；已成交订单必须走原单退货退款。'),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('返回'),
      ),
      FilledButton(
        key: const Key('confirmOrderCancellation'),
        onPressed: _reason.text.trim().isEmpty
            ? null
            : () => Navigator.pop(
                context,
                _OrderCancelInput(_reasonCode, _reason.text.trim()),
              ),
        child: const Text('确认取消'),
      ),
    ],
  );
}

class _FocusScannerIntent extends Intent {
  const _FocusScannerIntent();
}

class _HoldSaleIntent extends Intent {
  const _HoldSaleIntent();
}

class _SettleCashIntent extends Intent {
  const _SettleCashIntent();
}

String _money(int minor) {
  final absolute = minor.abs();
  final text =
      '${absolute ~/ 100}.${(absolute % 100).toString().padLeft(2, '0')}';
  return '${minor < 0 ? '-' : ''}¥$text';
}

String _shortDigest(String value) =>
    value.length <= 12 ? value : '${value.substring(0, 12)}…';
