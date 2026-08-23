import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/session/domain/saas_restriction_notice.dart';

void main() {
  test('ACTIVE 不显示限制，暂停状态只保留服务端受控恢复白名单', () {
    const active = SaasRestrictionNotice(
      lifecycleState: 'ACTIVE',
      reasonCode: 'ALLOWED',
      allowedRecoveryCapabilities: {},
    );
    expect(active.visible, isFalse);

    const suspended = SaasRestrictionNotice(
      lifecycleState: 'SUSPENDED',
      reasonCode: 'SUBSCRIPTION_OR_OPERATOR_SUSPENDED',
      allowedRecoveryCapabilities: {'REFUND', 'SALE', 'LEGAL_EXPORT'},
    );
    expect(suspended.visible, isTrue);
    expect(suspended.title, '商户服务已暂停');
    expect(suspended.effectiveRecoveryCapabilities, {'REFUND', 'LEGAL_EXPORT'});
    expect(suspended.allowsRecovery('REFUND'), isTrue);
    expect(suspended.allowsRecovery('SALE'), isFalse);
  });

  test('停用、注销申请和逻辑注销使用明确提示', () {
    for (final entry in {
      'DEACTIVATED': '商户服务已停用',
      'TERMINATION_REQUESTED': '商户正在办理注销',
      'TERMINATED_LOGICAL': '商户已逻辑注销',
      'UNKNOWN': '商户服务当前受限',
    }.entries) {
      final notice = SaasRestrictionNotice(
        lifecycleState: entry.key,
        reasonCode: 'SERVER_DECISION',
        allowedRecoveryCapabilities: const {'AUDIT'},
      );
      expect(notice.title, entry.value);
    }
  });
}
