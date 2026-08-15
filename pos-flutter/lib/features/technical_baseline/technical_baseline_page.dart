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
                  'T0 技术基线',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineMedium,
                ),
                const SizedBox(height: 12),
                const Text(
                  '当前仅验证应用壳、构建链路和设备适配边界，尚未启用收银业务。',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 24),
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
