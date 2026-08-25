import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../checkout/application/checkout_local_service.dart';
import '../../checkout/domain/ulid_generator.dart';
import '../../session/domain/pos_session_models.dart';
import '../application/pos_shift_application_service.dart';
import '../domain/shift_models.dart';

/// 正式本地班次应用服务；只通过 Checkout Owner 生成班次、现金与 Outbox 事实。
final class LocalPosShiftApplicationService
    implements PosShiftApplicationService {
  LocalPosShiftApplicationService({
    required this.database,
    required this.checkout,
    required this.ulids,
    required this.configVersion,
    Set<PosPermission>? permissions,
    this.authorizationRef = 'LOCKED_AUTHORIZATION',
    this.synchronizeAfterClose,
    DateTime Function()? now,
  }) : permissions = Set.unmodifiable(permissions ?? const <PosPermission>{}),
       _now = now ?? DateTime.now;

  final PosLocalDatabase database;
  final CheckoutLocalService checkout;
  final UlidGenerator ulids;
  final int configVersion;
  final Set<PosPermission> permissions;
  final String authorizationRef;

  /// 关班本地事实提交后触发的同步观察；失败时保留原 Outbox，禁止把已完成关班伪装为失败。
  final Future<void> Function()? synchronizeAfterClose;
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
      try {
        await synchronizeAfterClose?.call();
      } on Object {
        // POS 采用本地优先：网络或服务端不可用时，关班事实仍由原 Outbox 身份等待后续观察。
      }
    } on PosDomainException catch (error) {
      throw PosSessionFailure(error.code, error.message);
    }
  }

  @override
  Future<ShiftOperationResult> recordCashMovement({
    required String shiftId,
    required ShiftCashMovementType movementType,
    required String amount,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async {
    _requirePermission(PosPermission.cashManage);
    try {
      final version = _openShiftVersion(shiftId);
      return checkout.recordShiftCashMovement(
        commandId: ulids.next(),
        idempotencyKey: idempotencyKey,
        shiftId: shiftId,
        movementType: movementType,
        amountMinor: _parsePositiveYuan(amount),
        reasonCode: reasonCode,
        reasonText: reasonText,
        authorizationRef: authorizationRef,
        expectedVersion: version,
        occurredAt: _now().toUtc(),
      );
    } on PosDomainException catch (error) {
      throw PosSessionFailure(error.code, error.message);
    }
  }

  @override
  Future<ShiftOperationResult> requestNoSaleDrawer({
    required String shiftId,
    required String reasonCode,
    required String reasonText,
    required String idempotencyKey,
  }) async {
    _requirePermission(PosPermission.drawerNoSale);
    try {
      return checkout.requestNoSaleDrawer(
        commandId: ulids.next(),
        idempotencyKey: idempotencyKey,
        shiftId: shiftId,
        reasonCode: reasonCode,
        reasonText: reasonText,
        authorizationRef: authorizationRef,
        expectedVersion: _openShiftVersion(shiftId),
        occurredAt: _now().toUtc(),
      );
    } on PosDomainException catch (error) {
      throw PosSessionFailure(error.code, error.message);
    }
  }

  int _openShiftVersion(String shiftId) {
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
    return rows.single['record_version']! as int;
  }

  void _requirePermission(PosPermission permission) {
    if (!permissions.contains(permission)) {
      throw const PosSessionFailure('PERMISSION_DENIED', '当前员工没有执行该班次操作的权限。');
    }
    if (!RegExp(r'^[A-Za-z0-9._:-]{16,128}$').hasMatch(authorizationRef)) {
      throw const PosSessionFailure(
        'AUTHORIZATION_CONTEXT_INVALID',
        '员工授权上下文无效。',
      );
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

int _parsePositiveYuan(String source) {
  final value = _parseYuan(source);
  if (value <= 0) {
    throw const PosSessionFailure('SHIFT_CASH_INVALID', '班次现金金额必须大于零。');
  }
  return value;
}
