/// 商业 V1 行业体验代码；只影响页面提示和布局，不承载任何领域规则。
enum PosIndustryExperience {
  convenience,
  snackDiscount,
  communitySupermarket,
  safeGeneric,
}

/// POS 行业体验只读配置。
///
/// `templateVersion` 来自已验证的运行配置；本对象不得用于租户、门店、
/// 权限、价格、促销、库存、成本或退款决策。
final class IndustryExperienceProfile {
  const IndustryExperienceProfile({
    required this.industry,
    required this.templateVersion,
    required this.label,
    required this.primaryHint,
    required this.checkoutHint,
    required this.supported,
  });

  final PosIndustryExperience industry;
  final String templateVersion;
  final String label;
  final String primaryHint;
  final String checkoutHint;
  final bool supported;

  /// 从签名配置中的模板版本解析体验。未知版本回退到不猜测规则的安全通用模式。
  factory IndustryExperienceProfile.resolve(String templateVersion) {
    final normalized = templateVersion.trim().toUpperCase();
    if (normalized.startsWith('CONVENIENCE')) {
      return IndustryExperienceProfile(
        industry: PosIndustryExperience.convenience,
        templateVersion: templateVersion,
        label: '便利店快捷模式',
        primaryHint: '连续扫码后可使用键盘快速结算',
        checkoutHint: '关注断网状态、同步积压与交班现金差异',
        supported: true,
      );
    }
    if (normalized.startsWith('SNACK_DISCOUNT')) {
      return IndustryExperienceProfile(
        industry: PosIndustryExperience.snackDiscount,
        templateVersion: templateVersion,
        label: '零食折扣模式',
        primaryHint: '支持连续扫码、多件录入与称重商品提示',
        checkoutHint: '价格、单位与促销结果以正式报价快照为准',
        supported: true,
      );
    }
    if (normalized.startsWith('COMMUNITY_SUPERMARKET')) {
      return IndustryExperienceProfile(
        industry: PosIndustryExperience.communitySupermarket,
        templateVersion: templateVersion,
        label: '社区超市模式',
        primaryHint: '优先使用分类搜索、多单位与称重商品提示',
        checkoutHint: '退货、库存与成本仍由各 Owner 正式事实决定',
        supported: true,
      );
    }
    return IndustryExperienceProfile(
      industry: PosIndustryExperience.safeGeneric,
      templateVersion: templateVersion,
      label: '安全通用模式',
      primaryHint: '行业体验版本未识别，仅保留通用收银入口',
      checkoutHint: '不会猜测价格、促销、库存或退款规则',
      supported: false,
    );
  }
}
