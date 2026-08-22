import 'package:flutter/material.dart';

import '../application/pos_tender_controller.dart';
import '../domain/pos_tender_models.dart';

/// PAY-004 组合支付页；展示每份原始身份和状态，不允许 UI 自行判定资金成功。
final class PosTenderPage extends StatefulWidget {
  const PosTenderPage({required this.controller, super.key});
  final PosTenderController controller;

  @override
  State<PosTenderPage> createState() => _PosTenderPageState();
}

final class _PosTenderPageState extends State<PosTenderPage> {
  late final List<TextEditingController> _electronic;
  late final TextEditingController _cash;
  late final TextEditingController _tendered;

  @override
  void initState() {
    super.initState();
    final total = widget.controller.source.receivableAmountMinor;
    final first = total ~/ 2;
    _electronic = [TextEditingController(text: first.toString())];
    _cash = TextEditingController(text: (total - first).toString());
    _tendered = TextEditingController(text: (total - first).toString());
  }

  @override
  void dispose() {
    for (final controller in _electronic) {
      controller.dispose();
    }
    _cash.dispose();
    _tendered.dispose();
    super.dispose();
  }

  Future<void> _run(Future<PosTenderPageState> action) async {
    setState(() {});
    await action;
    if (mounted) setState(() {});
  }

  void _addElectronicAllocation() {
    if (_electronic.length >= 7) return;
    setState(() => _electronic.add(TextEditingController(text: '0')));
  }

  void _removeElectronicAllocation(int index) {
    if (_electronic.length <= 1) return;
    final removed = _electronic.removeAt(index);
    removed.dispose();
    setState(() {});
  }

  List<PosTenderAllocationDraft> _drafts() => [
    for (var index = 0; index < _electronic.length; index++)
      PosTenderAllocationDraft(
        sequenceNo: index + 1,
        tenderType: PosTenderType.electronic,
        amountMinor: int.tryParse(_electronic[index].text) ?? 0,
      ),
    PosTenderAllocationDraft(
      sequenceNo: _electronic.length + 1,
      tenderType: PosTenderType.cash,
      amountMinor: int.tryParse(_cash.text) ?? 0,
    ),
  ];

  @override
  Widget build(BuildContext context) {
    final state = widget.controller.state;
    final plan = state.plan;
    return Scaffold(
      appBar: AppBar(title: const Text('组合支付')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text('订单：${widget.controller.source.orderRef}'),
            Text(
              '应收：${_money(widget.controller.source.receivableAmountMinor)}',
            ),
            const SizedBox(height: 12),
            const Text(
              '份额严格串行；结果未知只能刷新原计划。电子支付尚未解阻时会失败关闭。',
              key: Key('tenderSafetyBoundary'),
            ),
            const SizedBox(height: 16),
            if (plan == null) ...[
              for (var index = 0; index < _electronic.length; index++) ...[
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        key: Key('electronicAmount-${index + 1}'),
                        controller: _electronic[index],
                        keyboardType: TextInputType.number,
                        decoration: InputDecoration(
                          labelText: '电子份额 ${index + 1}（分）',
                          border: const OutlineInputBorder(),
                        ),
                      ),
                    ),
                    if (_electronic.length > 1)
                      IconButton(
                        key: Key('removeElectronic-${index + 1}'),
                        tooltip: '移除电子份额',
                        onPressed: () => _removeElectronicAllocation(index),
                        icon: const Icon(Icons.remove_circle_outline),
                      ),
                  ],
                ),
                const SizedBox(height: 12),
              ],
              OutlinedButton.icon(
                key: const Key('addElectronicAllocation'),
                onPressed: _electronic.length >= 7
                    ? null
                    : _addElectronicAllocation,
                icon: const Icon(Icons.add),
                label: const Text('增加电子份额（最多 7 份）'),
              ),
              const SizedBox(height: 12),
              TextField(
                key: const Key('cashAmount'),
                controller: _cash,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: '最后现金份额（分）',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                key: const Key('freezeTenderPlan'),
                onPressed: state.busy
                    ? null
                    : () => _run(widget.controller.freeze(_drafts())),
                icon: const Icon(Icons.lock),
                label: const Text('冻结支付计划'),
              ),
            ] else ...[
              _planHeader(plan),
              const SizedBox(height: 12),
              for (final allocation in plan.allocations)
                _allocationCard(allocation, state.busy),
              FilledButton.tonalIcon(
                key: const Key('refreshTenderPlan'),
                onPressed: state.busy
                    ? null
                    : () => _run(widget.controller.refresh()),
                icon: const Icon(Icons.refresh),
                label: const Text('查询原计划状态'),
              ),
            ],
            if (state.safeMessage != null) ...[
              const SizedBox(height: 12),
              Text(
                '${state.safeMessage}\n错误码：${state.errorCode}',
                key: const Key('tenderSafeError'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _planHeader(PosTenderPlanView plan) => Card(
    child: ListTile(
      key: const Key('tenderPlanStatus'),
      title: Text('计划 ${plan.planRef}'),
      subtitle: Text(
        '状态 ${plan.status.name} · 已成功 ${_money(plan.succeededAmountMinor)} · 占额 ${_money(plan.occupiedAmountMinor)}',
      ),
    ),
  );

  Widget _allocationCard(PosTenderAllocationView item, bool busy) {
    final cash = item.tenderType == PosTenderType.cash;
    return Card(
      key: Key('tenderAllocation-${item.sequenceNo}'),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              '第 ${item.sequenceNo} 份 · ${cash ? '现金' : '电子'} · ${_money(item.amountMinor)} · ${item.status.name}',
            ),
            if (cash) ...[
              const SizedBox(height: 8),
              TextField(
                key: const Key('cashTenderedAmount'),
                controller: _tendered,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: '顾客实付现金（分）',
                  border: OutlineInputBorder(),
                ),
              ),
            ],
            const SizedBox(height: 8),
            FilledButton(
              key: Key('collectTender-${item.sequenceNo}'),
              onPressed:
                  busy || item.status != PosTenderAllocationStatus.planned
                  ? null
                  : () => _run(
                      widget.controller.collect(
                        item.allocationRef,
                        tenderedMinor: cash
                            ? int.tryParse(_tendered.text)
                            : null,
                      ),
                    ),
              child: Text(cash ? '请求确认现金份额' : '请求电子份额'),
            ),
          ],
        ),
      ),
    );
  }

  String _money(int minor) => '¥${(minor / 100).toStringAsFixed(2)}';
}
