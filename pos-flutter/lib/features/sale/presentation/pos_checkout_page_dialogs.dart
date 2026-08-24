part of 'pos_checkout_page.dart';

/// 人工优惠与现金结算输入对话框；只返回用户意图，不计算领域金额。
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
