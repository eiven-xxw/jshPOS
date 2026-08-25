import 'package:flutter/material.dart';

import '../application/pos_exchange_controller.dart';
import '../domain/pos_exchange_models.dart';

/// EXG-001 正式换货关联页；分别展示退款和新销售，不展示或创建“净额资金事实”。
final class PosExchangePage extends StatefulWidget {
  const PosExchangePage({
    required this.controller,
    this.allowCreate = true,
    this.allowApprove = false,
    this.allowRecover = false,
    super.key,
  });
  final PosExchangeController controller;
  final bool allowCreate;
  final bool allowApprove;
  final bool allowRecover;
  @override
  State<PosExchangePage> createState() => _PosExchangePageState();
}

class _PosExchangePageState extends State<PosExchangePage> {
  String _reasonCode = 'CUSTOMER_EXCHANGE';
  PosExchangePageState get _state => widget.controller.state;

  Future<void> _run(Future<PosExchangePageState> future) async {
    setState(() {});
    await future;
    if (mounted) setState(() {});
  }

  Future<void> _confirmAndRun({
    required String title,
    required String impact,
    required Future<PosExchangePageState> Function() operation,
  }) async {
    final confirmed = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: Text(impact),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            key: const Key('confirmExchangeAction'),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('确认执行'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) await _run(operation());
  }

  @override
  Widget build(BuildContext context) {
    final source = widget.controller.source;
    final view = _state.view;
    return Scaffold(
      appBar: AppBar(title: const Text('基础换货关联')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const Text(
              '换货由“原单退货退款 + 新销售 + 只追加关联”组成，两个金额各自保留权威事实。',
              key: Key('exchangeOwnerBoundary'),
            ),
            const Text(
              '离线或重启后只查询原 exchangeRef 与两腿 Owner 检查点；禁止生成净额资金事实或替代命令。',
              key: Key('exchangeOfflineRecoveryBoundary'),
            ),
            const SizedBox(height: 16),
            _leg(
              title: '原单退货退款',
              identity: source.originalReturn.returnRef,
              amount: source.originalReturn.refundableAmountMinor,
              status: source.originalReturn.status.name,
            ),
            _leg(
              title: '新销售',
              identity: source.newSale.orderRef,
              amount: source.newSale.receivableAmountMinor,
              status: 'COMPLETED',
            ),
            const SizedBox(height: 12),
            Text(
              '展示差额 ${_money(source.newSale.receivableAmountMinor - source.originalReturn.refundableAmountMinor)}（仅展示）',
              key: const Key('exchangeDisplayDifference'),
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              key: const Key('exchangeReason'),
              initialValue: _reasonCode,
              items: const [
                DropdownMenuItem(
                  value: 'CUSTOMER_EXCHANGE',
                  child: Text('顾客换购'),
                ),
                DropdownMenuItem(
                  value: 'WRONG_ITEM_EXCHANGE',
                  child: Text('错拿换购'),
                ),
                DropdownMenuItem(
                  value: 'QUALITY_EXCHANGE',
                  child: Text('质量问题换购'),
                ),
              ],
              onChanged: _state.busy
                  ? null
                  : (value) => setState(() => _reasonCode = value!),
              decoration: const InputDecoration(
                labelText: '换货原因',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 16),
            if (_state.safeMessage != null)
              Text(
                '${_state.safeMessage}\n错误码：${_state.errorCode}',
                key: const Key('exchangeSafeError'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            if (view == null && _state.recoverableExchangeRef == null)
              FilledButton.icon(
                key: const Key('createExchangeLink'),
                onPressed: _state.busy || !widget.allowCreate
                    ? null
                    : () => _confirmAndRun(
                        title: '建立换货关联确认',
                        impact:
                            '退货 ${source.originalReturn.returnRef} 与新销售 ${source.newSale.orderRef} 将建立只追加关联；两笔金额不做净额重算。',
                        operation: () => widget.controller.create(_reasonCode),
                      ),
                icon: const Icon(Icons.swap_horiz),
                label: const Text('建立只追加换货关联'),
              ),
            if (view != null) ...[
              _status(view),
              const SizedBox(height: 12),
              if (view.status == PosExchangeStatus.draft)
                FilledButton.icon(
                  key: const Key('approveExchange'),
                  onPressed: _state.busy || !widget.allowApprove
                      ? null
                      : () => _confirmAndRun(
                          title: '独立审批换货',
                          impact:
                              '换货 ${view.exchangeRef}；版本 ${view.recordVersion}。审批只推进既有 Saga，不重建退款或销售命令。',
                          operation: () =>
                              widget.controller.approve('SUPERVISOR_APPROVED'),
                        ),
                  icon: const Icon(Icons.verified_user),
                  label: Text(widget.allowApprove ? '独立审批换货' : '需要换货审批权限'),
                ),
              if (view.status == PosExchangeStatus.manualRecoveryRequired) ...[
                FilledButton.tonalIcon(
                  key: const Key('recoverExchangeReturn'),
                  onPressed: _state.busy || !widget.allowRecover
                      ? null
                      : () => _confirmAndRun(
                          title: '恢复原退货检查点',
                          impact: '换货 ${view.exchangeRef}；只观察并恢复原 RETURN 检查点。',
                          operation: () => widget.controller.recover(
                            'RETURN',
                            'AUTHORIZED_RECOVERY',
                          ),
                        ),
                  icon: const Icon(Icons.restore),
                  label: const Text('恢复原退货检查点'),
                ),
                FilledButton.tonalIcon(
                  key: const Key('recoverExchangeSale'),
                  onPressed: _state.busy || !widget.allowRecover
                      ? null
                      : () => _confirmAndRun(
                          title: '恢复新销售检查点',
                          impact: '换货 ${view.exchangeRef}；只观察并恢复原 SALE 检查点。',
                          operation: () => widget.controller.recover(
                            'SALE',
                            'AUTHORIZED_RECOVERY',
                          ),
                        ),
                  icon: const Icon(Icons.restore_page),
                  label: const Text('恢复新销售检查点'),
                ),
              ],
              FilledButton.tonalIcon(
                key: const Key('refreshExchangeStatus'),
                onPressed: _state.busy || view.status.terminal
                    ? null
                    : () => _run(widget.controller.refresh()),
                icon: const Icon(Icons.refresh),
                label: const Text('查询原换货检查点'),
              ),
            ] else if (_state.recoverableExchangeRef case final ref?)
              FilledButton.tonalIcon(
                key: const Key('recoverUnknownExchange'),
                onPressed: _state.busy
                    ? null
                    : () => _run(widget.controller.refresh(ref)),
                icon: const Icon(Icons.manage_search),
                label: const Text('只查询原换货命令'),
              ),
          ],
        ),
      ),
    );
  }

  Widget _leg({
    required String title,
    required String identity,
    required int amount,
    required String status,
  }) => Card(
    child: ListTile(
      title: Text(title),
      subtitle: Text('$identity\n$status'),
      trailing: Text(
        _money(amount),
        style: Theme.of(context).textTheme.titleLarge,
      ),
    ),
  );

  Widget _status(PosExchangeView view) => Card(
    color: view.status == PosExchangeStatus.completed
        ? Theme.of(context).colorScheme.primaryContainer
        : null,
    child: ListTile(
      key: const Key('exchangeSagaStatus'),
      title: Text(view.status.safeLabel),
      subtitle: Text(
        '${view.exchangeRef}\n版本 ${view.recordVersion} · ${view.correlationRef}',
      ),
    ),
  );
}

String _money(int minor) {
  final sign = minor < 0 ? '-' : '';
  final absolute = minor.abs();
  return '$sign¥${absolute ~/ 100}.${(absolute % 100).toString().padLeft(2, '0')}';
}
