import 'package:flutter/material.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

import '../features/technical_baseline/technical_baseline_page.dart';

class JshposApp extends StatelessWidget {
  const JshposApp({super.key, this.deviceGateway});

  final PosDeviceGateway? deviceGateway;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '鲸熵汇收银系统',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0D6B5B)),
        useMaterial3: true,
      ),
      home: TechnicalBaselinePage(
        deviceGateway: deviceGateway ?? const PosDeviceAdapter(),
      ),
    );
  }
}
