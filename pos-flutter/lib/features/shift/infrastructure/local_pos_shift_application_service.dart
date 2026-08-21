import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../checkout/application/checkout_local_service.dart';
import '../../checkout/domain/ulid_generator.dart';
import '../../session/domain/pos_session_models.dart';
import '../application/pos_shift_application_service.dart';

/// 正式本地班次应用服务；只通过 Checkout Owner 生成班次、现金与 Outbox 事实。
final class LocalPosShiftApplicationService
    implements PosShiftApplicationService {
  LocalPosShiftApplicationService({
    required this.database,
    required this.checkout,
    required this.ulids,
    required this.configVersion,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final PosLocalDatabase database;
  final CheckoutLocalService checkout;
  final UlidGenerator ulids;
  final int configVersion;
  final DateTime Function() _now;

  @override
  Future<PosShiftContext> open({
    required String businessDate,
    required String openingCash,
    required String idempotencyKey,
  }) async {
    try {
      final at = _now().toUtc();
      final result = checkout.openShift(
        commandId: ulids.next(),
        idempotencyKey: idempotencyKey,
        businessDate: businessDate,
        openingCashMinor: _parseYuan(openingCash),
        configVersion: configVersion,
        occurredAt: at,
      );
      return PosShiftContext(
        shiftId: result.shiftId,
        businessDate: result.businessDate,
        status: result.status,
        openedAt: at,
      );
    } on PosDomainException catch (error) {
      throw PosSessionFailure(error.code, error.message);
    }
  }

  @override
  Future<void> close({
    required String shiftId,
    required String actualCash,
    required String idempotencyKey,
  }) async {
    final rows = database.database.select(
      '''SELECT record_version FROM local_shift WHERE tenant_id=? AND store_id=?
         AND terminal_id=? AND cashier_id=? AND shift_id=? AND status='OPEN' ''',
      [
        database.binding.tenantId,
        database.binding.storeId,
        database.binding.terminalId,
        database.binding.cashierId,
        shiftId,
      ],
    );
    if (rows.length != 1) {
      throw const PosSessionFailure('SHIFT_STATE_CONFLICT', '班次不存在或已经关闭。');
    }
    try {
      checkout.closeShift(
        commandId: ulids.next(),
        idempotencyKey: idempotencyKey,
        shiftId: shiftId,
        actualCashMinor: _parseYuan(actualCash),
        expectedVersion: rows.single['record_version']! as int,
        occurredAt: _now().toUtc(),
      );
    } on PosDomainException catch (error) {
      throw PosSessionFailure(error.code, error.message);
    }
  }
}

int _parseYuan(String source) {
  final match = RegExp(r'^(0|[1-9][0-9]{0,12})(?:\.([0-9]{1,2}))?$')
      .firstMatch(source.trim());
  if (match == null) {
    throw const PosSessionFailure('SHIFT_CASH_INVALID', '现金金额格式不正确。');
  }
  final fraction = (match.group(2) ?? '').padRight(2, '0');
  return int.parse(match.group(1)!) * 100 + int.parse(fraction);
}
