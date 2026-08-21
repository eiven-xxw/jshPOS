import 'package:flutter/material.dart';

import '../../session/domain/pos_session_models.dart';
import '../application/pos_shift_application_service.dart';
import '../domain/shift_models.dart';

/// 班次现金管理页面只发送具名应用命令，不直接访问 SQLite 或设备通道。
final class PosCashManagementPage extends StatefulWidget {
  const PosCashManagementPage({
    required this.shiftId,
    required this.service,
    required this.allowCashMovement,
    required this.allowDrawerRequest,
    super.key,
  });

  final String shiftId;
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

  @override
  void dispose() {
    _amount.dispose();
    _reason.dispose();
    super.dispose();
  }

  Future<void> _recordCash() async {
    if (_busy || !widget.allowCashMovement) return;
    await _run(
      () => widget.service.recordCashMovement(
        shiftId: widget.shiftId,
        movementType: _type,
        amount: _amount.text,
        reasonCode: 'SHIFT_OPERATION',
        reasonText: _reason.text.trim(),
        idempotencyKey: _newKey('cash'),
      ),
    );
  }

  Future<void> _requestDrawer() async {
    if (_busy || !widget.allowDrawerRequest) return;
    await _run(
      () => widget.service.requestNoSaleDrawer(
        shiftId: widget.shiftId,
        reasonCode: 'NO_SALE_DRAWER',
        reasonText: _reason.text.trim(),
        idempotencyKey: _newKey('drawer'),
      ),
    );
  }

  Future<void> _run(Future<ShiftOperationResult> Function() command) async {
    if (_reason.text.trim().isEmpty) {
      _message('必须填写业务原因。');
      return;
    }
    setState(() => _busy = true);
    try {
      final result = await command();
      _amount.clear();
      _reason.clear();
      _message(
        result.deviceExecutionStatus == 'BLOCKED_EXTERNAL'
            ? '钱箱请求已审计；真实外设未解阻，未执行开箱。'
            : '班次现金事实已记录，当前理论现金 ¥${(result.theoreticalCashMinor / 100).toStringAsFixed(2)}。',
      );
    } on PosSessionFailure catch (error) {
      _message('${error.message}（${error.code}）');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _message(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  String _newKey(String kind) =>
      '$kind:${widget.shiftId}:${DateTime.now().toUtc().microsecondsSinceEpoch}';

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('班次现金与钱箱')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        const Text('现金动作会进入班次理论现金并形成只追加审计；非销售开钱箱当前只记录请求。'),
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
