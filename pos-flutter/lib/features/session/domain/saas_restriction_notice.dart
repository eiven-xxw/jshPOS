/// 商业租户生命周期受限提示；只展示服务端结论，不能在 POS 本地推导或覆盖套餐授权。
final class SaasRestrictionNotice {
  const SaasRestrictionNotice({
    required this.lifecycleState,
    required this.reasonCode,
    required this.allowedRecoveryCapabilities,
    this.subscriptionState,
    this.subscriptionAccessMode,
    this.subscriptionEndsAt,
    this.subscriptionGraceEndsAt,
  });

  final String lifecycleState;
  final String reasonCode;
  final Set<String> allowedRecoveryCapabilities;

  /// 服务端权威订阅状态；POS 不根据本地时间推导。
  final String? subscriptionState;

  /// 服务端权威访问模式；未知模式必须显示受限。
  final String? subscriptionAccessMode;

  /// 服务端返回的期限，仅用于展示。
  final DateTime? subscriptionEndsAt;

  /// 服务端返回的宽限期限，仅用于展示。
  final DateTime? subscriptionGraceEndsAt;

  static const Set<String> controlledRecovery = {
    'REFUND',
    'PAYMENT_AND_REFUND_QUERY',
    'RECONCILIATION',
    'AUDIT',
    'BACKUP_RESTORE',
    'LEGAL_EXPORT',
    'DATA_MIGRATION',
    'DATA_DELETION_REQUEST',
  };

  /// ACTIVE 不显示限制；其他状态必须显示失败关闭提示。
  bool get visible =>
      lifecycleState != 'ACTIVE' ||
      (subscriptionAccessMode != null &&
          subscriptionAccessMode != 'NORMAL' &&
          subscriptionAccessMode != 'GRACE');

  String get title {
    if (lifecycleState == 'ACTIVE') {
      return switch (subscriptionAccessMode) {
        'RECOVERY_ONLY' => '订阅已到期或暂停',
        'TERMINATED_RECOVERY' => '订阅已逻辑终止',
        _ => '商户服务当前受限',
      };
    }
    return switch (lifecycleState) {
      'SUSPENDED' => '商户服务已暂停',
      'DEACTIVATED' => '商户服务已停用',
      'TERMINATION_REQUESTED' => '商户正在办理注销',
      'TERMINATED_LOGICAL' => '商户已逻辑注销',
      _ => '商户服务当前受限',
    };
  }

  /// 受限状态下仅展示服务端明确允许且属于固定恢复白名单的能力。
  Set<String> get effectiveRecoveryCapabilities =>
      allowedRecoveryCapabilities.intersection(controlledRecovery);

  bool allowsRecovery(String featureCode) =>
      visible && effectiveRecoveryCapabilities.contains(featureCode);
}
