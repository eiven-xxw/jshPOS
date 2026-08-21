import '../../checkout/domain/ulid_generator.dart';

final class ShiftPolicy {
  const ShiftPolicy({required this.cashDifferenceApprovalMinor});

  final int cashDifferenceApprovalMinor;
}

/// 班次非销售现金动作；正负方向由领域固定，页面不得自行传入带符号金额。
enum ShiftCashMovementType {
  cashIn('CASH_IN'),
  cashOut('CASH_OUT'),
  safeDrop('SAFE_DROP');

  const ShiftCashMovementType(this.wireCode);
  final String wireCode;

  int signed(int amountMinor) => this == cashIn ? amountMinor : -amountMinor;
}

/// 班次现金/钱箱命令的不可变结果，支持原幂等键恢复而不重建命令。
final class ShiftOperationResult {
  const ShiftOperationResult({
    required this.operationId,
    required this.shiftId,
    required this.operationType,
    required this.theoreticalCashMinor,
    required this.recordVersion,
    required this.deviceExecutionStatus,
    this.signedAmountMinor,
    this.duplicate = false,
  });

  factory ShiftOperationResult.fromJson(
    Map<String, Object?> json, {
    bool duplicate = false,
  }) => ShiftOperationResult(
    operationId: json['operationId']! as String,
    shiftId: json['shiftId']! as String,
    operationType: json['operationType']! as String,
    signedAmountMinor: json['signedAmountMinor'] as int?,
    theoreticalCashMinor: json['theoreticalCashMinor']! as int,
    recordVersion: json['recordVersion']! as int,
    deviceExecutionStatus: json['deviceExecutionStatus']! as String,
    duplicate: duplicate,
  );

  final String operationId;
  final String shiftId;
  final String operationType;
  final int? signedAmountMinor;
  final int theoreticalCashMinor;
  final int recordVersion;
  final String deviceExecutionStatus;
  final bool duplicate;

  Map<String, Object?> toJson() => {
    'operationId': operationId,
    'shiftId': shiftId,
    'operationType': operationType,
    'signedAmountMinor': signedAmountMinor,
    'theoreticalCashMinor': theoreticalCashMinor,
    'recordVersion': recordVersion,
    'deviceExecutionStatus': deviceExecutionStatus,
  };
}

/// Result of the trusted supervisor authentication boundary. Credentials and
/// PINs are never retained; only the opaque proof reference is persisted.
final class SupervisorSession {
  const SupervisorSession.fromTrustedAuthentication({
    required this.supervisorId,
    required this.supervisorName,
    required this.authProofRef,
    required this.authenticatedAt,
  });

  final String supervisorId;
  final String supervisorName;
  final String authProofRef;
  final DateTime authenticatedAt;

  void validate(String cashierId, DateTime occurredAt) {
    final age = occurredAt.toUtc().difference(authenticatedAt.toUtc());
    if (supervisorId.isEmpty ||
        supervisorId == cashierId ||
        supervisorName.isEmpty ||
        !RegExp(r'^[A-Za-z0-9._:-]{16,128}$').hasMatch(authProofRef) ||
        age.isNegative ||
        age > const Duration(minutes: 5)) {
      throw StateError(
        'SHIFT_APPROVER_SEPARATION_REQUIRED: trusted supervisor session is invalid',
      );
    }
  }
}

final class ShiftCloseApproval {
  const ShiftCloseApproval({
    required this.approvalId,
    required this.approverId,
    required this.reasonCode,
    required this.reasonText,
    required this.actualCashMinor,
    required this.differenceMinor,
  });

  factory ShiftCloseApproval.fromJson(
    Map<String, Object?> json, {
    bool duplicate = false,
  }) => ShiftCloseApproval(
    approvalId: json['approvalId']! as String,
    approverId: json['approverId']! as String,
    reasonCode: json['reasonCode']! as String,
    reasonText: json['reasonText']! as String,
    actualCashMinor: json['actualCashMinor']! as int,
    differenceMinor: json['differenceMinor']! as int,
  );

  final String approvalId;
  final String approverId;
  final String reasonCode;
  final String reasonText;
  final int actualCashMinor;
  final int differenceMinor;

  void validate(String cashierId) {
    if (!UlidGenerator.isCanonical(approvalId) ||
        approverId.isEmpty ||
        approverId == cashierId ||
        reasonCode.isEmpty ||
        reasonText.isEmpty) {
      throw StateError(
        'SHIFT_DIFFERENCE_APPROVAL_REQUIRED: invalid independent approval',
      );
    }
  }

  Map<String, Object?> toJson() => {
    'approvalId': approvalId,
    'approverId': approverId,
    'reasonCode': reasonCode,
    'reasonText': reasonText,
    'actualCashMinor': actualCashMinor,
    'differenceMinor': differenceMinor,
  };
}

final class ShiftResult {
  const ShiftResult({
    required this.shiftId,
    required this.status,
    required this.businessDate,
    required this.theoreticalCashMinor,
    required this.recordVersion,
    this.actualCashMinor,
    this.differenceMinor,
    this.duplicate = false,
  });

  factory ShiftResult.fromJson(
    Map<String, Object?> json, {
    bool duplicate = false,
  }) => ShiftResult(
    shiftId: json['shiftId']! as String,
    status: json['status']! as String,
    businessDate: json['businessDate']! as String,
    theoreticalCashMinor: json['theoreticalCashMinor']! as int,
    actualCashMinor: json['actualCashMinor'] as int?,
    differenceMinor: json['differenceMinor'] as int?,
    recordVersion: json['recordVersion']! as int,
    duplicate: duplicate,
  );

  final String shiftId;
  final String status;
  final String businessDate;
  final int theoreticalCashMinor;
  final int? actualCashMinor;
  final int? differenceMinor;
  final int recordVersion;
  final bool duplicate;

  Map<String, Object?> toJson() => {
    'shiftId': shiftId,
    'status': status,
    'businessDate': businessDate,
    'theoreticalCashMinor': theoreticalCashMinor,
    'actualCashMinor': actualCashMinor,
    'differenceMinor': differenceMinor,
    'recordVersion': recordVersion,
  };
}
