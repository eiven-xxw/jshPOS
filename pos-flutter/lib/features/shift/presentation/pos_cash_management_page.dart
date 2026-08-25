import 'package:flutter/material.dart';

import '../../session/domain/pos_session_models.dart';
import '../application/pos_shift_application_service.dart';
import '../domain/shift_models.dart';

/// 班次现金管理页面只发送具名应用命令，不直接访问 SQLite 或设备通道。
final class PosCashManagementPage extends StatefulWidget {
  const PosCashManagementPage({
    required this.shiftId,
    required this.businessDate,
    required this.service,
    required this.allowCashMovement,
    required this.allowDrawerRequest,
    super.key,
  });

  final String shiftId;
  final String businessDate;
  final PosShiftApplicationService service;
  final bool allowCashMovement;
  final bool allowDrawerRequest;

  @override
  State<PosCashManagementPage> createState() => _PosCashManagementPageState();
}

class _PosCashManagementPageState extends State<PosCashManagementPage> {
  final _amount = TextEditingController();
  final _reason = TextEditingController();
  ShiftCashMovementType _type = ShiftCashMovementType.cashIn;
  bool _busy = false;
  final Map<String, String> _operationKeys = <String, String>{};
  int _keySequence = 0;

  @override
  void dispose() {
    _amount.dispose();
    _reason.dispose();
    super.dispose();
  }

  Future<void> _recordCash() async {
    if (_busy || !widget.allowCashMovement) return;
    final reason = _reason.text.trim();
    final operation = 'cash:${_type.wireCode}:${_amount.text}:$reason';
    if (!await _confirm(
      title: '确认班次现金动作',
      impact:
          '班次 ${widget.shiftId} · 业务日 ${widget.businessDate}\n'
          '${_type.wireCode} ¥${_amount.text}；将形成只追加现金事实和审计。',
    )) {
      return;
    }
    await _run(
      () => widget.service.recordCashMovement(
        shiftId: widget.shiftId,
        movementType: _type,
        amount: _amount.text,
        reasonCode: 'SHIFT_OPERATION',
        reasonText: reason,
        idempotencyKey: _keyFor(operation),
      ),
      operation: operation,
    );
  }

  Future<void> _requestDrawer() async {
    if (_busy || !widget.allowDrawerRequest) return;
    final reason = _reason.text.trim();
    final operation = 'drawer:$reason';
    if (!await _confirm(
      title: '确认非销售开钱箱请求',
      impact:
          '班次 ${widget.shiftId} · 业务日 ${widget.businessDate}\n'
          '仅形成受审计请求；真实钱箱仍为 BLOCKED_EXTERNAL，不执行开箱。',
    )) {
      return;
    }
    await _run(
      () => widget.service.requestNoSaleDrawer(
        shiftId: widget.shiftId,
        reasonCode: 'NO_SALE_DRAWER',
        reasonText: reason,
        idempotencyKey: _keyFor(operation),
      ),
      operation: operation,
    );
  }

  Future<void> _run(
    Future<ShiftOperationResult> Function() command, {
    required String operation,
  }) async {
    if (_reason.text.trim().isEmpty) {
      _message('必须填写业务原因。');
      return;
    }
    setState(() => _busy = true);
    try {
      final result = await command();
      _operationKeys.remove(operation);
      _amount.clear();
      _reason.clear();
      _message(
        result.deviceExecutionStatus == 'BLOCKED_EXTERNAL'
            ? '钱箱请求已审计；真实外设未解阻，未执行开箱。'
            : '班次现金事实已记录，当前理论现金 ¥${(result.theoreticalCashMinor / 100).toStringAsFixed(2)}。',
      );
    } on PosSessionFailure catch (error) {
      _message(
        '${error.message}（${error.code}）\n原操作键已保留，只能恢复该命令：${_keyFor(operation)}',
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _message(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  String _keyFor(String operation) => _operationKeys.putIfAbsent(
    operation,
    () => 'r3c:${widget.shiftId}:${++_keySequence}',
  );

  Future<bool> _confirm({
    required String title,
    required String impact,
  }) async =>
      await showDialog<bool>(
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
              key: const Key('confirmCashOperation'),
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('确认执行'),
            ),
          ],
        ),
      ) ??
      false;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('班次现金与钱箱')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        const Text('现金动作会进入班次理论现金并形成只追加审计；非销售开钱箱当前只记录请求。'),
        Text(
          '冻结班次 ${widget.shiftId} · 业务日 ${widget.businessDate}',
          key: const Key('cashFrozenContext'),
        ),
        const Text(
          '真实钱箱：BLOCKED_EXTERNAL / UNAVAILABLE；离线时仅写入本地正式事务并沿原键恢复。',
          key: Key('drawerExternalBoundary'),
        ),
        const SizedBox(height: 16),
        DropdownButtonFormField<ShiftCashMovementType>(
          initialValue: _type,
          decoration: const InputDecoration(labelText: '现金动作'),
          items: const [
            DropdownMenuItem(
              value: ShiftCashMovementType.cashIn,
              child: Text('现金存入'),
            ),
            DropdownMenuItem(
              value: ShiftCashMovementType.cashOut,
              child: Text('现金取出'),
            ),
            DropdownMenuItem(
              value: ShiftCashMovementType.safeDrop,
              child: Text('缴款 / 安全投库'),
            ),
          ],
          onChanged: _busy ? null : (value) => setState(() => _type = value!),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _amount,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: '金额（元）'),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _reason,
          maxLength: 256,
          decoration: const InputDecoration(labelText: '业务原因'),
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          key: const Key('recordShiftCashMovement'),
          onPressed: _busy || !widget.allowCashMovement ? null : _recordCash,
          icon: const Icon(Icons.payments_outlined),
          label: const Text('记录现金动作'),
        ),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          key: const Key('requestNoSaleDrawer'),
          onPressed: _busy || !widget.allowDrawerRequest
              ? null
              : _requestDrawer,
          icon: const Icon(Icons.inventory_2_outlined),
          label: const Text('登记非销售开钱箱请求'),
        ),
      ],
    ),
  );
}
