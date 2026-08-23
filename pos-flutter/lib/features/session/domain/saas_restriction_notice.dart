/// 商业租户生命周期受限提示；只展示服务端结论，不能在 POS 本地推导或覆盖套餐授权。
final class SaasRestrictionNotice {
  const SaasRestrictionNotice({
    required this.lifecycleState,
    required this.reasonCode,
    required this.allowedRecoveryCapabilities,
  });

  final String lifecycleState;
  final String reasonCode;
  final Set<String> allowedRecoveryCapabilities;

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
  bool get visible => lifecycleState != 'ACTIVE';

  String get title => switch (lifecycleState) {
    'SUSPENDED' => '商户服务已暂停',
    'DEACTIVATED' => '商户服务已停用',
    'TERMINATION_REQUESTED' => '商户正在办理注销',
    'TERMINATED_LOGICAL' => '商户已逻辑注销',
    _ => '商户服务当前受限',
  };

  /// 受限状态下仅展示服务端明确允许且属于固定恢复白名单的能力。
  Set<String> get effectiveRecoveryCapabilities =>
      allowedRecoveryCapabilities.intersection(controlledRecovery);

  bool allowsRecovery(String featureCode) =>
      visible && effectiveRecoveryCapabilities.contains(featureCode);
}
