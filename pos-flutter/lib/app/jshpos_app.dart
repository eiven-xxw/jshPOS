import 'package:flutter/material.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

import '../features/checkout/domain/ulid_generator.dart';
import '../features/session/application/pos_session_repository.dart';
import '../features/session/application/pos_session_service.dart';
import '../features/session/infrastructure/locked_pos_session_repository.dart';
import '../features/session/presentation/pos_session_shell.dart';
import '../features/shift/application/pos_shift_application_service.dart';
import '../features/shift/infrastructure/locked_pos_shift_application_service.dart';
import '../features/sale/application/pos_sale_application_service.dart';
import '../features/sale/infrastructure/locked_pos_sale_application_service.dart';
import '../features/return_refund/application/pos_return_application_service.dart';
import '../features/return_refund/infrastructure/locked_pos_return_application_service.dart';
import '../features/exchange/application/pos_exchange_application_service.dart';
import '../features/exchange/infrastructure/locked_pos_exchange_application_service.dart';
import '../features/tender/application/pos_tender_controller.dart';

class JshposApp extends StatelessWidget {
  const JshposApp({
    super.key,
    this.industryTemplateVersion = 'CONVENIENCE_V1',
    this.deviceGateway,
    this.sessionRepository,
    this.saleService,
    this.returnService,
    this.exchangeService,
    this.shiftService,
    this.tenderController,
  });

  final PosDeviceGateway? deviceGateway;
  final String industryTemplateVersion;
  final PosSessionRepository? sessionRepository;
  final PosSaleApplicationService? saleService;
  final PosReturnApplicationService? returnService;
  final PosExchangeApplicationService? exchangeService;
  final PosShiftApplicationService? shiftService;

  /// 仅在 Order Owner 已冻结订单与可信班次上下文后注入；空值必须显示不可用。
  final PosTenderController? tenderController;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '鲸熵汇收银系统',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0D6B5B)),
        useMaterial3: true,
        visualDensity: VisualDensity.standard,
        filledButtonTheme: const FilledButtonThemeData(
          style: ButtonStyle(minimumSize: WidgetStatePropertyAll(Size(48, 48))),
        ),
      ),
      home: PosSessionShell(
        industryTemplateVersion: industryTemplateVersion,
        sessionService: PosSessionService(
          deviceGateway: deviceGateway ?? const PosDeviceAdapter(),
          repository: sessionRepository ?? const LockedPosSessionRepository(),
          correlationId: UlidGenerator().next,
        ),
        saleService: saleService ?? const LockedPosSaleApplicationService(),
        returnService:
            returnService ?? const LockedPosReturnApplicationService(),
        exchangeService:
            exchangeService ?? const LockedPosExchangeApplicationService(),
        shiftService: shiftService ?? const LockedPosShiftApplicationService(),
        tenderController: tenderController,
      ),
    );
  }
}
