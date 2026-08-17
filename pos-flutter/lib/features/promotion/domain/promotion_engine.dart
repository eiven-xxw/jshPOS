import 'dart:math' show max;

import '../../checkout/domain/exact_quantity.dart';

/// Gate 5A 准入的基础促销算子白名单。
enum PromotionRuleType {
  specialPrice,
  percentOff,
  amountOff,
  nthItemDiscount,
  bundlePrice,
  thresholdAmountOff,
  thresholdQuantityOff,
}

/// 规则互斥与叠加策略；不存在隐式默认脚本。
enum PromotionStackMode { exclusive, stackable, bestOfGroup }

/// 以非负整数和显式小数位保存的精确十进制值。
final class ExactDecimal {
  const ExactDecimal._(this.unscaled, this.scale);

  factory ExactDecimal.parse(String source, {int maximumScale = 8}) {
    if (!RegExp(r'^(0|[1-9][0-9]{0,18})(\.[0-9]+)?$').hasMatch(source)) {
      throw const FormatException('PRM-DECIMAL-001: invalid canonical decimal');
    }
    final parts = source.split('.');
    final scale = parts.length == 1 ? 0 : parts[1].length;
    if (scale > maximumScale) {
      throw const FormatException('PRM-DECIMAL-002: decimal scale exceeded');
    }
    final digits = parts.length == 1
        ? parts.first
        : '${parts.first}${parts[1]}';
    if (digits.length > 20) {
      throw const FormatException('PRM-DECIMAL-004: decimal precision exceeded');
    }
    return ExactDecimal._(BigInt.parse(digits), scale);
  }

  final BigInt unscaled;
  final int scale;

  bool get isPositive => unscaled > BigInt.zero;

  int multiplyMinorHalfUp(int minor) =>
      _safeMinor(_roundHalfUp(BigInt.from(minor) * unscaled, _pow10(scale)));

  int floorDivide(ExactDecimal other) {
    if (!other.isPositive) throw const FormatException('PRM-DECIMAL-003');
    final common = max(scale, other.scale);
    final left = unscaled * _pow10(common - scale);
    final right = other.unscaled * _pow10(common - other.scale);
    return (left ~/ right).toInt();
  }

  static int compareSum(List<ExactDecimal> values, ExactDecimal other) {
    final common = values.fold(
      other.scale,
      (value, item) => max(value, item.scale),
    );
    final sum = values.fold<BigInt>(
      BigInt.zero,
      (value, item) => value + item.unscaled * _pow10(common - item.scale),
    );
    return sum.compareTo(other.unscaled * _pow10(common - other.scale));
  }
}

/// 冻结基础/门店价后的购物行；金额为最小货币单位整数。
final class PromotionLine {
  PromotionLine({
    required this.lineId,
    required this.lineNo,
    required this.skuId,
    required this.categoryId,
    required this.brandId,
    required this.quantity,
    required this.unitPriceMinor,
  }) {
    if (!RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(lineId) ||
        lineNo <= 0 ||
        !RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(skuId) ||
        (categoryId != null &&
            !RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(categoryId!)) ||
        (brandId != null &&
            !RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(brandId!)) ||
        !quantity.isPositive) {
      throw const FormatException('PRM-ENGINE-001: invalid line');
    }
    MoneyRules.requireMinor(unitPriceMinor, 'unitPriceMinor');
  }

  final String lineId;
  final int lineNo;
  final String skuId;
  final String? categoryId;
  final String? brandId;
  final ExactDecimal quantity;
  final int unitPriceMinor;
}

/// 多维作用域；非空维度之间按 AND 匹配。
final class PromotionScope {
  const PromotionScope({
    this.skuIds = const {},
    this.categoryIds = const {},
    this.brandIds = const {},
    this.storeIds = const {},
    this.channels = const {},
    this.businessDays = const {},
  });

  final Set<String> skuIds;
  final Set<String> categoryIds;
  final Set<String> brandIds;
  final Set<String> storeIds;
  final Set<String> channels;
  final Set<int> businessDays;

  bool matches(
    PromotionLine line,
    String storeId,
    String channel,
    DateTime businessTime,
  ) =>
      (skuIds.isEmpty || skuIds.contains(line.skuId)) &&
      (categoryIds.isEmpty || categoryIds.contains(line.categoryId)) &&
      (brandIds.isEmpty || brandIds.contains(line.brandId)) &&
      (storeIds.isEmpty || storeIds.contains(storeId)) &&
      (channels.isEmpty || channels.contains(channel)) &&
      (businessDays.isEmpty || businessDays.contains(businessTime.weekday));
}

/// 固定组合价中的 SKU 与每套精确数量。
final class BundleComponent {
  const BundleComponent(this.skuId, this.quantity);
  final String skuId;
  final ExactDecimal quantity;
}

/// 各白名单算子的互斥参数集合，由引擎做残留字段校验。
final class PromotionBenefit {
  const PromotionBenefit({
    this.amountMinor,
    this.discountRate,
    this.nth,
    this.thresholdMinor,
    this.thresholdQuantity,
    this.bundlePriceMinor,
    this.bundleComponents = const [],
  });
  final int? amountMinor;
  final ExactDecimal? discountRate;
  final int? nth;
  final int? thresholdMinor;
  final ExactDecimal? thresholdQuantity;
  final int? bundlePriceMinor;
  final List<BundleComponent> bundleComponents;
}

/// 已发布的不可变规则版本。
final class PromotionRule {
  PromotionRule({
    required this.ruleVersionId,
    required this.ruleType,
    required this.priority,
    required this.stackMode,
    required this.effectiveFrom,
    required this.effectiveTo,
    required this.scope,
    required this.benefit,
    this.exclusiveGroup,
  }) {
    if (!RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(ruleVersionId) ||
        (effectiveTo != null && !effectiveTo!.isAfter(effectiveFrom)) ||
        (stackMode == PromotionStackMode.bestOfGroup &&
            (exclusiveGroup == null || exclusiveGroup!.isEmpty))) {
      throw const FormatException('PRM-RULE-001: invalid rule');
    }
  }
  final String ruleVersionId;
  final PromotionRuleType ruleType;
  final int priority;
  final PromotionStackMode stackMode;
  final String? exclusiveGroup;
  final DateTime effectiveFrom;
  final DateTime? effectiveTo;
  final PromotionScope scope;
  final PromotionBenefit benefit;

  bool activeAt(DateTime value) =>
      !value.isBefore(effectiveFrom) &&
      (effectiveTo == null || value.isBefore(effectiveTo!));
}

/// 规则采用或排除的机器可读解释。
final class PromotionExplanation {
  const PromotionExplanation(this.sourceId, this.code);
  final String sourceId;
  final String code;

  @override
  bool operator ==(Object other) =>
      other is PromotionExplanation &&
      other.sourceId == sourceId &&
      other.code == code;
  @override
  int get hashCode => Object.hash(sourceId, code);
}

/// 单一规则实际贡献的守恒行级调整。
final class PromotionAdjustment {
  const PromotionAdjustment(
    this.sourceId,
    this.amountMinor,
    this.lineAllocations,
  );
  final String sourceId;
  final int amountMinor;
  final Map<String, int> lineAllocations;
}

/// 确定性促销计算结果。
final class PromotionQuote {
  const PromotionQuote({
    required this.grossAmountMinor,
    required this.discountAmountMinor,
    required this.payableAmountMinor,
    required this.lineDiscounts,
    required this.appliedRuleIds,
    required this.explanations,
    required this.adjustments,
  });
  final int grossAmountMinor;
  final int discountAmountMinor;
  final int payableAmountMinor;
  final Map<String, int> lineDiscounts;
  final List<String> appliedRuleIds;
  final List<PromotionExplanation> explanations;
  final List<PromotionAdjustment> adjustments;
}

/// Java/Dart 共用黄金向量的纯函数促销引擎。
final class PromotionEngine {
  static const engineVersion = 'promotion-engine-1.0.0';

  PromotionQuote quote({
    required DateTime businessTime,
    required String storeId,
    required String channel,
    required List<PromotionLine> lines,
    required List<PromotionRule> rules,
  }) {
    if (lines.isEmpty || lines.length > 500) {
      throw const FormatException('PRM-ENGINE-002: invalid quote');
    }
    for (final rule in rules) {
      validateRule(rule);
    }
    final state = <String, _MutableLine>{};
    final lineNumbers = <int>{};
    for (final line in [
      ...lines,
    ]..sort((a, b) => a.lineNo.compareTo(b.lineNo))) {
      if (!lineNumbers.add(line.lineNo) || state.containsKey(line.lineId)) {
        throw const FormatException('PRM-ENGINE-003: duplicate line');
      }
      state[line.lineId] = _MutableLine(
        line,
        line.quantity.multiplyMinorHalfUp(line.unitPriceMinor),
      );
    }
    final candidates = [...rules]
      ..sort((a, b) {
        final priority = b.priority.compareTo(a.priority);
        return priority != 0
            ? priority
            : a.ruleVersionId.compareTo(b.ruleVersionId);
      });
    final applied = <String>[];
    final explanations = <PromotionExplanation>[];
    final adjustments = <PromotionAdjustment>[];
    final best = <String, String>{};
    for (final rule in candidates) {
      if (!rule.activeAt(businessTime)) {
        explanations.add(
          PromotionExplanation(rule.ruleVersionId, 'TIME_NOT_MATCHED'),
        );
        continue;
      }
      if (rule.stackMode == PromotionStackMode.bestOfGroup) {
        final selected = best.putIfAbsent(
          rule.exclusiveGroup!,
          () => _selectBest(
            rule.exclusiveGroup!,
            businessTime,
            storeId,
            channel,
            candidates,
            state,
          ),
        );
        if (selected != rule.ruleVersionId) {
          explanations.add(
            PromotionExplanation(rule.ruleVersionId, 'LOWER_BENEFIT'),
          );
          continue;
        }
      }
      final eligible = state.values
          .where(
            (line) =>
                !line.locked &&
                rule.scope.matches(line.source, storeId, channel, businessTime),
          )
          .toList();
      if (eligible.isEmpty &&
          state.values.any(
            (line) =>
                line.locked &&
                rule.scope.matches(line.source, storeId, channel, businessTime),
          )) {
        explanations.add(
          PromotionExplanation(rule.ruleVersionId, 'EXCLUSIVE_CONFLICT'),
        );
        continue;
      }
      final before = {
        for (final line in eligible) line.source.lineId: line.discount,
      };
      final discount = _apply(rule, eligible, explanations);
      if (discount > 0) {
        applied.add(rule.ruleVersionId);
        explanations.add(PromotionExplanation(rule.ruleVersionId, 'APPLIED'));
        adjustments.add(
          PromotionAdjustment(rule.ruleVersionId, discount, {
            for (final line in eligible)
              if (line.discount - before[line.source.lineId]! > 0)
                line.source.lineId: line.discount - before[line.source.lineId]!,
          }),
        );
        if (rule.stackMode == PromotionStackMode.exclusive) {
          for (final line in eligible) {
            line.locked = true;
          }
        }
      }
    }
    final gross = _sumMinor(state.values.map((line) => line.gross));
    final discount = _sumMinor(state.values.map((line) => line.discount));
    final lineDiscounts = {
      for (final entry in state.entries) entry.key: entry.value.discount,
    };
    if (_sumMinor(lineDiscounts.values) != discount ||
        discount > gross) {
      throw StateError('PRM-ENGINE-004: amount conservation failed');
    }
    return PromotionQuote(
      grossAmountMinor: gross,
      discountAmountMinor: discount,
      payableAmountMinor: gross - discount,
      lineDiscounts: lineDiscounts,
      appliedRuleIds: applied,
      explanations: explanations,
      adjustments: adjustments,
    );
  }

  String _selectBest(
    String group,
    DateTime at,
    String storeId,
    String channel,
    List<PromotionRule> candidates,
    Map<String, _MutableLine> state,
  ) {
    final scored =
        candidates
            .where(
              (rule) =>
                  rule.stackMode == PromotionStackMode.bestOfGroup &&
                  rule.exclusiveGroup == group &&
                  rule.activeAt(at),
            )
            .map((rule) {
              final copies = state.values
                  .where(
                    (line) =>
                        !line.locked &&
                        rule.scope.matches(line.source, storeId, channel, at),
                  )
                  .map((line) => line.copy())
                  .toList();
              return (rule: rule, amount: _apply(rule, copies, []));
            })
            .toList()
          ..sort((a, b) {
            final amount = b.amount.compareTo(a.amount);
            return amount != 0
                ? amount
                : a.rule.ruleVersionId.compareTo(b.rule.ruleVersionId);
          });
    return scored.first.rule.ruleVersionId;
  }

  int _apply(
    PromotionRule rule,
    List<_MutableLine> lines,
    List<PromotionExplanation> explanations,
  ) {
    if (lines.isEmpty) return 0;
    final value = rule.benefit;
    switch (rule.ruleType) {
      case PromotionRuleType.specialPrice:
        return _item(
          lines,
          (line) =>
              _current(line) -
              line.source.quantity.multiplyMinorHalfUp(
                _money(value.amountMinor),
              ),
        );
      case PromotionRuleType.percentOff:
        return _item(
          lines,
          (line) =>
              _rate(value.discountRate).multiplyMinorHalfUp(_current(line)),
        );
      case PromotionRuleType.amountOff:
        return _item(lines, (line) => _money(value.amountMinor));
      case PromotionRuleType.nthItemDiscount:
        final nth = value.nth ?? 0;
        if (nth < 2) throw const FormatException('PRM-RULE-012');
        return _item(lines, (line) {
          final count = line.source.quantity.floorDivide(
            ExactDecimal._(BigInt.from(nth), 0),
          );
          return _rate(value.discountRate)
              .multiplyMinorHalfUp(_multiplyMinor(line.source.unitPriceMinor, count));
        });
      case PromotionRuleType.thresholdAmountOff:
        final current = _sumMinor(lines.map(_current));
        if (current < _money(value.thresholdMinor)) {
          explanations.add(
            PromotionExplanation(rule.ruleVersionId, 'THRESHOLD_NOT_MET'),
          );
          return 0;
        }
        return _allocate(lines, minInt(current, _money(value.amountMinor)));
      case PromotionRuleType.thresholdQuantityOff:
        final threshold = value.thresholdQuantity;
        if (threshold == null || !threshold.isPositive) {
          throw const FormatException('PRM-RULE-015');
        }
        if (ExactDecimal.compareSum(
              lines.map((line) => line.source.quantity).toList(),
              threshold,
            ) <
            0) {
          explanations.add(
            PromotionExplanation(rule.ruleVersionId, 'THRESHOLD_NOT_MET'),
          );
          return 0;
        }
        final current = _sumMinor(lines.map(_current));
        return _allocate(lines, minInt(current, _money(value.amountMinor)));
      case PromotionRuleType.bundlePrice:
        if (value.bundleComponents.isEmpty) {
          throw const FormatException('PRM-RULE-017');
        }
        var sets = 0x7fffffff;
        final selected = <_MutableLine>[];
        for (final component in value.bundleComponents) {
          final matches = lines.where(
            (line) => line.source.skuId == component.skuId,
          );
          if (matches.isEmpty) {
            explanations.add(
              PromotionExplanation(rule.ruleVersionId, 'BUNDLE_NOT_MET'),
            );
            return 0;
          }
          final line = matches.first;
          sets = minInt(
            sets,
            line.source.quantity.floorDivide(component.quantity),
          );
          selected.add(line);
        }
        if (sets <= 0) {
          explanations.add(
            PromotionExplanation(rule.ruleVersionId, 'BUNDLE_NOT_MET'),
          );
          return 0;
        }
        final current = _sumMinor(selected.map(_current));
        return _allocate(
          selected,
          maxInt(
            0,
            current -
                minInt(current, _multiplyMinor(_money(value.bundlePriceMinor), sets)),
          ),
        );
    }
  }

  int _item(List<_MutableLine> lines, int Function(_MutableLine) calculator) {
    var total = 0;
    for (final line in lines) {
      final amount = minInt(_current(line), maxInt(0, calculator(line)));
      line.discount = _addMinor(line.discount, amount);
      total = _addMinor(total, amount);
    }
    return total;
  }

  int _allocate(List<_MutableLine> lines, int amount) {
    if (amount == 0) return 0;
    final weighted = lines.where((line) => _current(line) > 0).toList();
    final total = _sumMinor(weighted.map(_current));
    final shares = weighted.map((line) {
      final numerator = BigInt.from(amount) * BigInt.from(_current(line));
      return _Share(
        line,
        (numerator ~/ BigInt.from(total)).toInt(),
        numerator.remainder(BigInt.from(total)),
      );
    }).toList();
    var remaining = amount - shares.fold(0, (sum, share) => sum + share.amount);
    shares.sort((a, b) {
      final remainder = b.remainder.compareTo(a.remainder);
      if (remainder != 0) return remainder;
      final lineNo = a.line.source.lineNo.compareTo(b.line.source.lineNo);
      if (lineNo != 0) return lineNo;
      final sku = a.line.source.skuId.compareTo(b.line.source.skuId);
      return sku != 0
          ? sku
          : a.line.source.lineId.compareTo(b.line.source.lineId);
    });
    for (var index = 0; remaining > 0; index++, remaining--) {
      shares[index % shares.length].amount++;
    }
    for (final share in shares) {
      share.line.discount = _addMinor(share.line.discount, share.amount);
    }
    return amount;
  }

  int _current(_MutableLine line) => line.gross - line.discount;
  int _money(int? value) =>
      MoneyRules.requireMinor(value ?? -1, 'promotionAmount');
  ExactDecimal _rate(ExactDecimal? value) {
    if (value == null || value.unscaled > _pow10(value.scale)) {
      throw const FormatException('PRM-RULE-020');
    }
    return value;
  }

  /// 在安装离线包或执行报价前校验白名单规则及其唯一合法参数组合。
  void validateRule(PromotionRule rule) {
    if (rule.priority < -100000 ||
        rule.priority > 100000 ||
        rule.scope.skuIds.length > 256 ||
        rule.scope.categoryIds.length > 256 ||
        rule.scope.brandIds.length > 256 ||
        rule.scope.storeIds.length > 256 ||
        rule.scope.channels.length > 16 ||
        rule.scope.businessDays.length > 7 ||
        !rule.scope.channels.every(
          const {'POS', 'MOBILE_POS', 'SELF_CHECKOUT'}.contains,
        ) ||
        !rule.scope.businessDays.every((day) => day >= 1 && day <= 7)) {
      throw const FormatException(
        'PRM-CAPABILITY-UNSUPPORTED: invalid scope or priority',
      );
    }
    final id = RegExp(r'^[1-9][0-9]{0,18}$');
    if (!rule.scope.skuIds.every(id.hasMatch) ||
        !rule.scope.categoryIds.every(id.hasMatch) ||
        !rule.scope.brandIds.every(id.hasMatch) ||
        !rule.scope.storeIds.every(id.hasMatch)) {
      throw const FormatException(
        'PRM-CAPABILITY-UNSUPPORTED: invalid scope identifier',
      );
    }
    final value = rule.benefit;
    switch (rule.ruleType) {
      case PromotionRuleType.specialPrice:
      case PromotionRuleType.amountOff:
        _money(value.amountMinor);
        _unused(
          value,
          rate: true,
          nth: true,
          thresholdAmount: true,
          thresholdQuantity: true,
          bundle: true,
        );
      case PromotionRuleType.percentOff:
        _rate(value.discountRate);
        _unused(
          value,
          amount: true,
          nth: true,
          thresholdAmount: true,
          thresholdQuantity: true,
          bundle: true,
        );
      case PromotionRuleType.nthItemDiscount:
        if (value.nth == null || value.nth! < 2 || value.nth! > 100) {
          throw const FormatException(
            'PRM-CAPABILITY-UNSUPPORTED: invalid nth',
          );
        }
        _rate(value.discountRate);
        _unused(
          value,
          amount: true,
          thresholdAmount: true,
          thresholdQuantity: true,
          bundle: true,
        );
      case PromotionRuleType.thresholdAmountOff:
        if (_money(value.thresholdMinor) <= 0) {
          throw const FormatException(
            'PRM-CAPABILITY-UNSUPPORTED: invalid amount threshold',
          );
        }
        _money(value.amountMinor);
        _unused(
          value,
          rate: true,
          nth: true,
          thresholdQuantity: true,
          bundle: true,
        );
      case PromotionRuleType.thresholdQuantityOff:
        if (value.thresholdQuantity == null ||
            !value.thresholdQuantity!.isPositive ||
            value.thresholdQuantity!.scale > 6) {
          throw const FormatException(
            'PRM-CAPABILITY-UNSUPPORTED: invalid quantity threshold',
          );
        }
        _money(value.amountMinor);
        _unused(
          value,
          rate: true,
          nth: true,
          thresholdAmount: true,
          bundle: true,
        );
      case PromotionRuleType.bundlePrice:
        _money(value.bundlePriceMinor);
        if (value.bundleComponents.isEmpty ||
            value.bundleComponents.length > 32) {
          throw const FormatException(
            'PRM-CAPABILITY-UNSUPPORTED: invalid bundle',
          );
        }
        final skus = <String>{};
        for (final component in value.bundleComponents) {
          if (!RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(component.skuId) ||
              !component.quantity.isPositive ||
              component.quantity.scale > 6 ||
              !skus.add(component.skuId)) {
            throw const FormatException(
              'PRM-CAPABILITY-UNSUPPORTED: invalid bundle component',
            );
          }
        }
        _unused(
          value,
          amount: true,
          rate: true,
          nth: true,
          thresholdAmount: true,
          thresholdQuantity: true,
        );
    }
  }

  void _unused(
    PromotionBenefit value, {
    bool amount = false,
    bool rate = false,
    bool nth = false,
    bool thresholdAmount = false,
    bool thresholdQuantity = false,
    bool bundle = false,
  }) {
    if ((amount && value.amountMinor != null) ||
        (rate && value.discountRate != null) ||
        (nth && value.nth != null) ||
        (thresholdAmount && value.thresholdMinor != null) ||
        (thresholdQuantity && value.thresholdQuantity != null) ||
        (bundle &&
            (value.bundlePriceMinor != null ||
                value.bundleComponents.isNotEmpty))) {
      throw const FormatException(
        'PRM-CAPABILITY-UNSUPPORTED: residual operator fields',
      );
    }
  }
}

final class _MutableLine {
  _MutableLine(this.source, this.gross);
  final PromotionLine source;
  final int gross;
  int discount = 0;
  bool locked = false;
  _MutableLine copy() {
    final result = _MutableLine(source, gross)..discount = discount;
    result.locked = locked;
    return result;
  }
}

final class _Share {
  _Share(this.line, this.amount, this.remainder);
  final _MutableLine line;
  int amount;
  final BigInt remainder;
}

BigInt _pow10(int scale) => BigInt.from(10).pow(scale);
BigInt _roundHalfUp(BigInt numerator, BigInt divisor) {
  final divided = numerator ~/ divisor;
  return divided +
      (numerator.remainder(divisor) * BigInt.two >= divisor
          ? BigInt.one
          : BigInt.zero);
}

int _safeMinor(BigInt value) {
  final maximum = BigInt.from(MoneyRules.maxSafeJsonInteger);
  if (value.isNegative || value > maximum) {
    throw ArgumentError.value(value, 'promotionMoney', '促销金额超出安全整数范围');
  }
  return MoneyRules.requireMinor(value.toInt(), 'promotionMoney');
}

int _sumMinor(Iterable<int> values) => _safeMinor(
  values.fold<BigInt>(BigInt.zero, (sum, value) => sum + BigInt.from(value)),
);

int _addMinor(int left, int right) =>
    _safeMinor(BigInt.from(left) + BigInt.from(right));

int _multiplyMinor(int left, int right) =>
    _safeMinor(BigInt.from(left) * BigInt.from(right));

int minInt(int left, int right) => left < right ? left : right;
int maxInt(int left, int right) => left > right ? left : right;
