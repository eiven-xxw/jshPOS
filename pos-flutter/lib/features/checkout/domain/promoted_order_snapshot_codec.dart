import 'dart:convert';

import 'package:crypto/crypto.dart';

import 'checkout_models.dart';

/// POS-006 与 ORD-003 共用的订单快照规范化编码器。
abstract final class PromotedOrderSnapshotCodec {
  /// 构造只包含冻结事实的订单快照，不执行促销计算。
  static Map<String, Object?> document({
    required PromotedCashSaleCommand command,
    required TrustedDeviceBinding binding,
    required String promotionSnapshotSha256,
  }) => {
    'schemaVersion': 2,
    'orderId': command.basket.orderId,
    'storeId': binding.storeId,
    'terminalId': binding.terminalId,
    'shiftId': command.shiftId,
    'cashierId': binding.cashierId,
    'businessDate': command.businessDate,
    'storeTimezone': binding.storeTimezone,
    'currency': 'CNY',
    'grossAmountMinor': command.grossAmountMinor,
    'discountAmountMinor': command.discountAmountMinor,
    'surchargeAmountMinor': command.surchargeAmountMinor,
    'receivableAmountMinor': command.receivableAmountMinor,
    'catalogVersion': command.catalogVersion,
    'priceVersion': command.priceVersion,
    'industryTemplateVersion': command.industryTemplateVersion,
    'promotionSnapshotId': command.promotionSnapshotId,
    'promotionSnapshotHash': 'sha256:$promotionSnapshotSha256',
    'quoteFingerprint': command.quoteFingerprint,
    'settlementFingerprint': command.settlementFingerprint,
    'promotionPackageVersion': command.packageVersion,
    'manualEventRefs': command.manualEventRefs,
    if (command.memberBenefitSnapshot != null)
      'memberBenefitSnapshot': command.memberBenefitSnapshot!.toJson(),
    'lines': command.lines.map((line) => line.toSnapshot()).toList(),
  };

  /// 对对象键递归排序；数组保持业务顺序，与服务端 CanonicalJson 一致。
  static String canonicalJson(Object? value) => jsonEncode(_sortJson(value));

  /// 生成不带前缀的小写 SHA-256 十六进制摘要。
  static String sha256Hex(Object? value) =>
      sha256.convert(utf8.encode(canonicalJson(value))).toString();

  static Object? _sortJson(Object? value) {
    if (value is Map) {
      final normalized = <String, Object?>{
        for (final entry in value.entries) '${entry.key}': entry.value,
      };
      final keys = normalized.keys.toList()..sort();
      return <String, Object?>{
        for (final key in keys) key: _sortJson(normalized[key]),
      };
    }
    if (value is Iterable) return value.map(_sortJson).toList();
    return value;
  }
}
