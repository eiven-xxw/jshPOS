import 'package:flutter/material.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

import '../features/checkout/domain/ulid_generator.dart';
import '../features/session/application/pos_session_repository.dart';
import '../features/session/application/pos_session_service.dart';
import '../features/session/infrastructure/locked_pos_session_repository.dart';
import '../features/session/presentation/pos_session_shell.dart';

class JshposApp extends StatelessWidget {
  const JshposApp({super.key, this.deviceGateway, this.sessionRepository});

  final PosDeviceGateway? deviceGateway;
  final PosSessionRepository? sessionRepository;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '鲸熵汇收银系统',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0D6B5B)),
        useMaterial3: true,
      ),
      home: PosSessionShell(
        sessionService: PosSessionService(
          deviceGateway: deviceGateway ?? const PosDeviceAdapter(),
          repository: sessionRepository ?? const LockedPosSessionRepository(),
          correlationId: UlidGenerator().next,
        ),
      ),
    );
  }
}
