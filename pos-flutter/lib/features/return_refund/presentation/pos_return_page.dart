import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../application/pos_return_controller.dart';
import '../domain/pos_return_models.dart';

/// POS-009 原单退货退款正式页面；只向 Controller 发送用户意图。
final class PosReturnPage extends StatefulWidget {
  const PosReturnPage({
    required this.controller,
    this.onStartExchange,
    super.key,
  });

  final PosReturnController controller;
  final ValueChanged<PosReturnSubmissionView>? onStartExchange;

  @override
  State<PosReturnPage> createState() => _PosReturnPageState();
}

class _PosReturnPageState extends State<PosReturnPage> {
  final _orderQuery = TextEditingController();
  final _supervisorCredential = TextEditingController();
  final Map<String, TextEditingController> _quantities = {};
  String _reasonCode = 'CUSTOMER_REQUEST';

  PosReturnPageState get _state => widget.controller.state;

  @override
  void dispose() {
    _orderQuery.dispose();
    _supervisorCredential.dispose();
    for (final controller in _quantities.values) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _run(Future<PosReturnPageState> future) async {
    if (mounted) setState(() {});
    await future;
    if (!mounted) return;
    _syncQuantities();
    setState(() {});
  }

  void _syncQuantities() {
    final active = <String>{};
    for (final line in _state.workspace?.lines ?? const <PosReturnLineView>[]) {
      active.add(line.lineRef);
      final controller = _quantities.putIfAbsent(
        line.lineRef,
        () => TextEditingController(),
      );
      if (controller.text != line.requestedQuantity) {
        controller.value = TextEditingValue(
          text: line.requestedQuantity,
          selection: TextSelection.collapsed(
            offset: line.requestedQuantity.length,
          ),
        );
      }
    }
    final stale = _quantities.keys
        .where((key) => !active.contains(key))
        .toList();
    for (final key in stale) {
      _quantities.remove(key)?.dispose();
    }
  }

  Future<void> _search() =>
      _run(widget.controller.searchOriginalOrder(_orderQuery.text));

  Future<void> _updateQuantity(PosReturnLineView line) => _run(
    widget.controller.changeQuantity(
      line.lineRef,
      _quantities[line.lineRef]?.text ?? '0',
    ),
  );

  Future<void> _confirmSubmit() async {
    final workspace = _state.workspace;
    if (workspace == null) return;
    final confirmed = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: const Text('确认原单退货退款'),
        content: Text(
          '原单 ${workspace.localOrderNo}\n'
          '恢复原优惠 ${_money(workspace.recoveredDiscountAmountMinor)}\n'
          '现金退款 ${_money(workspace.refundableAmountMinor)}\n\n'
          '提交后只能沿原申请查询或恢复，不能重新生成退款命令。',
        ),
        actions: [
          TextButton(
            key: const Key('cancelReturnSubmit'),
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: const Key('confirmReturnSubmit'),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确认提交'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final credential = _supervisorCredential.text;
    await _run(
      widget.controller.submitCashReturn(
        reasonCode: _reasonCode,
        supervisorCredential: credential.isEmpty ? null : credential,
      ),
    );
    _supervisorCredential.clear();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('原单退货退款')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          _searchPanel(),
          if (_state.safeMessage != null) ...[
            const SizedBox(height: 12),
            _SafeError(
              code: _state.errorCode ?? 'RETURN_FAILED',
              message: _state.safeMessage!,
            ),
          ],
          if (_state.workspace case final workspace?) ...[
            const SizedBox(height: 16),
            _orderSummary(workspace),
            const SizedBox(height: 16),
            _lineList(workspace),
            const SizedBox(height: 16),
            _submitPanel(workspace),
          ],
          if (_state.submission case final submission?) ...[
            const SizedBox(height: 16),
            _statusPanel(submission),
          ] else if (_state.phase == PosReturnPagePhase.unknown &&
              _state.recoverableReturnRef != null) ...[
            const SizedBox(height: 16),
            _unknownRecovery(_state.recoverableReturnRef!),
          ],
        ],
      ),
    ),
  );

  Widget _searchPanel() => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: TextField(
              key: const Key('returnOrderQuery'),
              controller: _orderQuery,
              enabled: !_state.busy,
              autofocus: true,
              maxLength: 64,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => _search(),
              decoration: const InputDecoration(
                labelText: '原订单号 / 小票号',
                hintText: '扫描小票或输入原订单号',
                prefixIcon: Icon(Icons.manage_search),
                border: OutlineInputBorder(),
              ),
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            height: 56,
            child: FilledButton.icon(
              key: const Key('searchOriginalOrder'),
              onPressed: _state.busy ? null : _search,
              icon: _state.phase == PosReturnPagePhase.searching
                  ? const SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.search),
              label: const Text('查询原单'),
            ),
          ),
        ],
      ),
    ),
  );

  Widget _orderSummary(PosReturnWorkspace workspace) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '原单 ${workspace.localOrderNo}',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 18,
            runSpacing: 8,
            children: [
              Text('${workspace.storeName} · 业务日 ${workspace.businessDate}'),
              Text(
                '结算：${workspace.settlementKind == 'CASH' ? '现金' : 'Provider 无关'}',
              ),
              Text('原应收 ${_money(workspace.originalReceivableAmountMinor)}'),
              Text('累计已退 ${_money(workspace.cumulativeRefundedAmountMinor)}'),
              Text('当前最多可退 ${_money(workspace.maximumRefundableAmountMinor)}'),
            ],
          ),
          const SizedBox(height: 8),
          Tooltip(
            message: workspace.promotionSnapshotSha256,
            child: Text(
              '原成交优惠快照 ${workspace.promotionSnapshotRef} · '
              '${workspace.promotionSnapshotSha256.substring(0, 12)}…',
              key: const Key('originalPromotionSnapshot'),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    ),
  );

  Widget _lineList(PosReturnWorkspace workspace) => Card(
    child: Padding(
      padding: const EdgeInsets.all(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text(
              '选择退货数量',
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
          const Divider(height: 1),
          ListView.separated(
            key: const Key('returnLines'),
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            itemCount: workspace.lines.length,
            separatorBuilder: (_, _) => const Divider(height: 1),
            itemBuilder: (context, index) => _line(workspace.lines[index]),
          ),
        ],
      ),
    ),
  );

  Widget _line(PosReturnLineView line) {
    final controller = _quantities.putIfAbsent(
      line.lineRef,
      () => TextEditingController(text: line.requestedQuantity),
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 12),
      child: Row(
        children: [
          Expanded(
            flex: 4,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(line.name, style: Theme.of(context).textTheme.titleMedium),
                Text('${line.skuCode} · ${line.unitName}'),
                Text(
                  '原购 ${line.originalQuantity}，累计已退 '
                  '${line.cumulativeReturnedQuantity}，最多可退 '
                  '${line.maximumReturnableQuantity}',
                ),
              ],
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 132,
            child: TextField(
              key: Key('returnQuantity:${line.lineRef}'),
              controller: controller,
              enabled: !_state.busy,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              inputFormatters: [
                FilteringTextInputFormatter.allow(RegExp(r'[0-9.]')),
                LengthLimitingTextInputFormatter(20),
              ],
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => _updateQuantity(line),
              decoration: const InputDecoration(
                labelText: '本次退货',
                border: OutlineInputBorder(),
              ),
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            height: 52,
            child: FilledButton.tonal(
              key: Key('applyReturnQuantity:${line.lineRef}'),
              onPressed: _state.busy ? null : () => _updateQuantity(line),
              child: const Text('更新'),
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 150,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text('退款 ${_money(line.refundableAmountMinor)}'),
                Text('恢复优惠 ${_money(line.recoveredDiscountMinor)}'),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _submitPanel(PosReturnWorkspace workspace) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: 20,
            runSpacing: 8,
            alignment: WrapAlignment.end,
            children: [
              Text('本次原价 ${_money(workspace.requestedGrossAmountMinor)}'),
              Text('恢复原优惠 -${_money(workspace.recoveredDiscountAmountMinor)}'),
              Text(
                '现金退款 ${_money(workspace.refundableAmountMinor)}',
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: DropdownButtonFormField<String>(
                  key: const Key('returnReason'),
                  initialValue: _reasonCode,
                  items: const [
                    DropdownMenuItem(
                      value: 'CUSTOMER_REQUEST',
                      child: Text('顾客要求'),
                    ),
                    DropdownMenuItem(
                      value: 'QUALITY_ISSUE',
                      child: Text('质量问题'),
                    ),
                    DropdownMenuItem(value: 'WRONG_ITEM', child: Text('商品错误')),
                  ],
                  onChanged: _state.busy
                      ? null
                      : (value) => setState(() => _reasonCode = value!),
                  decoration: const InputDecoration(
                    labelText: '退货原因',
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: TextField(
                  key: const Key('returnSupervisorCredential'),
                  controller: _supervisorCredential,
                  enabled: !_state.busy,
                  obscureText: true,
                  enableSuggestions: false,
                  autocorrect: false,
                  decoration: const InputDecoration(
                    labelText: '审批凭据（需要时）',
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              SizedBox(
                height: 56,
                child: FilledButton.icon(
                  key: const Key('submitCashReturn'),
                  onPressed: _state.busy || !workspace.canSubmit
                      ? null
                      : _confirmSubmit,
                  icon: const Icon(Icons.assignment_return),
                  label: const Text('提交现金退货'),
                ),
              ),
            ],
          ),
        ],
      ),
    ),
  );

  Widget _statusPanel(PosReturnSubmissionView submission) => Card(
    color: submission.status == PosReturnSagaStatus.completed
        ? Theme.of(context).colorScheme.primaryContainer
        : null,
    child: Padding(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            submission.status.safeLabel,
            key: const Key('returnSagaStatus'),
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 8),
          Text('退货申请 ${submission.returnRef}'),
          Text('退款金额 ${_money(submission.refundableAmountMinor)}'),
          Text('审计引用 ${submission.auditRef}'),
          Text('关联标识 ${submission.correlationRef}'),
          if (submission.duplicate) const Text('已返回原幂等结果'),
          const SizedBox(height: 12),
          if (!submission.status.terminal ||
              submission.status == PosReturnSagaStatus.failed)
            SizedBox(
              height: 52,
              child: FilledButton.tonalIcon(
                key: const Key('refreshReturnStatus'),
                onPressed: _state.busy
                    ? null
                    : () => _run(widget.controller.refreshStatus()),
                icon: const Icon(Icons.refresh),
                label: const Text('查询原申请状态'),
              ),
            ),
          if (submission.status == PosReturnSagaStatus.completed &&
              widget.onStartExchange != null)
            SizedBox(
              height: 52,
              child: FilledButton.icon(
                key: const Key('startExchangeSale'),
                onPressed: () => widget.onStartExchange!(submission),
                icon: const Icon(Icons.swap_horiz),
                label: const Text('继续选择换购商品'),
              ),
            ),
        ],
      ),
    ),
  );

  Widget _unknownRecovery(String returnRef) => Card(
    child: ListTile(
      leading: const Icon(Icons.help_outline, color: Colors.orange),
      title: const Text('退款结果未知'),
      subtitle: Text('只允许查询原申请 $returnRef，禁止重新发起退款。'),
      trailing: FilledButton.tonal(
        key: const Key('recoverUnknownReturn'),
        onPressed: _state.busy
            ? null
            : () => _run(widget.controller.refreshStatus(returnRef)),
        child: const Text('继续查询'),
      ),
    ),
  );
}

class _SafeError extends StatelessWidget {
  const _SafeError({required this.code, required this.message});

  final String code;
  final String message;

  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text('$message\n错误码：$code'),
    ),
  );
}

String _money(int minor) {
  final yuan = minor ~/ 100;
  final cents = (minor % 100).toString().padLeft(2, '0');
  return '¥$yuan.$cents';
}
