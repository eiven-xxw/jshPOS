part of 'pos_checkout_page.dart';

/// POS 主页面的纯展示组件、快捷键意图与格式化函数。
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
