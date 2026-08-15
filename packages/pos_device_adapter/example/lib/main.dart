import 'package:flutter/material.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

void main() => runApp(const AdapterExample());

class AdapterExample extends StatelessWidget {
  const AdapterExample({super.key, this.gateway});

  final PosDeviceGateway? gateway;

  @override
  Widget build(BuildContext context) {
    final gateway = this.gateway ?? const PosDeviceAdapter();
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('POS Device Adapter')),
        body: FutureBuilder<DeviceSnapshot>(
          future: gateway.snapshot(),
          builder: (context, snapshot) {
            if (snapshot.hasError) {
              return const Center(child: Text('Adapter unavailable'));
            }
            if (!snapshot.hasData) {
              return const Center(child: CircularProgressIndicator());
            }
            return Center(
              child: Text(
                '${snapshot.requireData.metadata.manufacturer} '
                '${snapshot.requireData.metadata.model}',
              ),
            );
          },
        ),
      ),
    );
  }
}
