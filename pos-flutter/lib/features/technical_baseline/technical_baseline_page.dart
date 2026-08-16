import 'package:flutter/material.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

class TechnicalBaselinePage extends StatefulWidget {
  const TechnicalBaselinePage({required this.deviceGateway, super.key});

  final PosDeviceGateway deviceGateway;

  @override
  State<TechnicalBaselinePage> createState() => _TechnicalBaselinePageState();
}

class _TechnicalBaselinePageState extends State<TechnicalBaselinePage> {
  late final Future<DeviceSnapshot> _snapshot = widget.deviceGateway.snapshot();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('鲸熵汇收银系统')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 720),
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Icon(
                  Icons.point_of_sale,
                  size: 72,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(height: 24),
                Text(
                  'T2 Gate 2 本地现金闭环',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: 12),
                const Text(
                  '班次、购物篮、现金订单、挂取单、交班核对和正式 SQLite 事务内核已装配。'
                  '商户、门店、终端、员工和签名配置完成可信激活前，界面保持安全锁定；远程同步与第三方支付仍未启用。',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 24),
                const _BaselineStatus(
                  label: '本地交易边界',
                  value: '原子事务已装配 · 等待可信设备激活',
                  color: Colors.orange,
                ),
                const SizedBox(height: 12),
                FutureBuilder<DeviceSnapshot>(
                  future: _snapshot,
                  builder: (context, snapshot) {
                    if (snapshot.hasError) {
                      return const _BaselineStatus(
                        label: '设备适配边界',
                        value: '不可用',
                        color: Colors.red,
                      );
                    }
                    if (!snapshot.hasData) {
                      return const Center(child: CircularProgressIndicator());
                    }
                    final device = snapshot.requireData;
                    return _BaselineStatus(
                      label: '设备适配边界',
                      value:
                          '${device.metadata.manufacturer} '
                          '${device.metadata.model} · '
                          '${device.capabilities.length} 项能力',
                      color: Colors.green,
                    );
                  },
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _BaselineStatus extends StatelessWidget {
  const _BaselineStatus({
    required this.label,
    required this.value,
    required this.color,
  });

  final String label;
  final String value;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        leading: Icon(Icons.check_circle, color: color),
        title: Text(label),
        subtitle: Text(value),
      ),
    );
  }
}
