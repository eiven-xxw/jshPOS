import 'package:flutter/material.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

import '../features/checkout/domain/ulid_generator.dart';
import '../features/session/application/pos_session_repository.dart';
import '../features/session/application/pos_session_service.dart';
import '../features/session/infrastructure/locked_pos_session_repository.dart';
import '../features/session/presentation/pos_session_shell.dart';
import '../features/sale/application/pos_sale_application_service.dart';
import '../features/sale/infrastructure/locked_pos_sale_application_service.dart';
import '../features/return_refund/application/pos_return_application_service.dart';
import '../features/return_refund/infrastructure/locked_pos_return_application_service.dart';

class JshposApp extends StatelessWidget {
  const JshposApp({
    super.key,
    this.deviceGateway,
    this.sessionRepository,
    this.saleService,
    this.returnService,
  });

  final PosDeviceGateway? deviceGateway;
  final PosSessionRepository? sessionRepository;
  final PosSaleApplicationService? saleService;
  final PosReturnApplicationService? returnService;

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
        saleService: saleService ?? const LockedPosSaleApplicationService(),
        returnService:
            returnService ?? const LockedPosReturnApplicationService(),
      ),
    );
  }
}
