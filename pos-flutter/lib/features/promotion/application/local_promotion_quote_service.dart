import 'dart:convert';

import 'package:crypto/crypto.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../../infrastructure/local_database/member_cache_store.dart';
import '../../checkout/domain/promoted_order_snapshot_codec.dart';
import '../../checkout/domain/ulid_generator.dart';
import '../../checkout/domain/checkout_models.dart';
import '../domain/promotion_engine.dart';
import '../domain/member_benefit_engine.dart';
import '../infrastructure/promotion_package_installer.dart';
import '../infrastructure/member_benefit_package_installer.dart';

/// POS 正式离线询价结果；报价身份、指纹和逐行来源分摊随后进入成交快照。
final class LocalPromotionQuoteResult {
  const LocalPromotionQuoteResult({
    required this.quoteId,
    required this.quoteFingerprint,
    required this.packageVersion,
    required this.quote,
    required this.sourceAllocationsByLine,
    this.memberBenefitSnapshot,
    this.duplicate = false,
  });

  final String quoteId;
  final String quoteFingerprint;
  final int packageVersion;
  final PromotionQuote quote;
  final Map<String, Map<String, int>> sourceAllocationsByLine;
  final MemberBenefitSettlementSnapshot? memberBenefitSnapshot;
  final bool duplicate;
}

/// 使用已验签活动规则包计算并原子保存不可变报价，不允许 UI 直接写 SQLite。
final class LocalPromotionQuoteService {
  LocalPromotionQuoteService({
    required this.database,
    required this.packageInstaller,
    required this.engine,
    required this.ulids,
    this.memberBenefitPackageInstaller,
    this.memberBenefitEngine,
  });

  final PosLocalDatabase database;
  final PromotionPackageInstaller packageInstaller;
  final PromotionEngine engine;
  final UlidGenerator ulids;
  final MemberBenefitPackageInstaller? memberBenefitPackageInstaller;
  final MemberBenefitEngine? memberBenefitEngine;

  Future<LocalPromotionQuoteResult> quote({
    required String pricingRequestId,
    required DateTime businessTime,
    required String channel,
    required List<PromotionLine> lines,
    bool memberBenefitEnabled = false,
    MemberCacheView? member,
    int capabilityConfigVersion = 1,
    String? capabilitySha256,
    Map<String, String> unitIdsByLine = const {},
  }) async {
    if (!UlidGenerator.isCanonical(pricingRequestId) ||
        !const {'POS', 'MOBILE_POS', 'SELF_CHECKOUT'}.contains(channel)) {
      throw const FormatException('PRM-QUOTE-LOCAL-001: invalid quote context');
    }
    final binding = database.binding..validate();
    final active = await packageInstaller.requireActive();
    final requestHash = _hash({
      'pricingRequestId': pricingRequestId,
      'storeId': binding.storeId,
      'terminalId': binding.terminalId,
      'channel': channel,
      'businessTime': businessTime.toUtc().toIso8601String(),
      'packageVersion': active.packageVersion,
      'memberBenefitEnabled': memberBenefitEnabled,
      'memberRef': member?.memberRef,
      'entitlementSnapshotId': member?.entitlementSnapshotId,
      'rightsDigest': member?.rightsDigest,
      'capabilityConfigVersion': capabilityConfigVersion,
      'capabilitySha256': capabilitySha256,
      'unitIdsByLine': unitIdsByLine,
      'lines': lines
          .map(
            (line) => {
              'lineId': line.lineId,
              'lineNo': line.lineNo,
              'skuId': line.skuId,
              'categoryId': line.categoryId,
              'brandId': line.brandId,
              'quantity': _decimal(line.quantity),
              'unitPriceMinor': line.unitPriceMinor,
            },
          )
          .toList(),
    });
    final existing = database.database.select(
      'SELECT * FROM local_promotion_quote WHERE tenant_id=? AND store_id=? AND terminal_id=? AND pricing_request_id=?',
      [binding.tenantId, binding.storeId, binding.terminalId, pricingRequestId],
    );
    if (existing.isNotEmpty) {
      if (existing.single['request_sha256'] != requestHash) {
        throw StateError('PRM-QUOTE-LOCAL-002: idempotency key reused');
      }
      return _read(existing.single['quote_id']! as String, duplicate: true);
    }
    final normalResult = engine.quote(
      businessTime: businessTime,
      storeId: binding.storeId,
      channel: channel,
      lines: lines,
      rules: active.rules,
    );
    final memberContext = memberBenefitEnabled
        ? await _memberContext(
            member: member,
            businessTime: businessTime,
            lines: lines,
            normal: normalResult,
            capabilityConfigVersion: capabilityConfigVersion,
            capabilitySha256: capabilitySha256,
            unitIdsByLine: unitIdsByLine,
          )
        : null;
    final result = memberContext?.quote ?? normalResult;
    final fingerprint = _resultHash(result);
    final quoteId = ulids.next();
    final byId = {for (final line in lines) line.lineId: line};
    final now = businessTime.toUtc().toIso8601String();
    database.transaction(() {
      database.database.execute(
        'INSERT INTO local_promotion_quote(quote_id,tenant_id,store_id,terminal_id,pricing_request_id,request_sha256,result_sha256,engine_version,package_version,business_time,gross_amount_minor,discount_amount_minor,payable_amount_minor,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,\'CALCULATED\',?)',
        [
          quoteId,
          binding.tenantId,
          binding.storeId,
          binding.terminalId,
          pricingRequestId,
          requestHash,
          fingerprint,
          PromotionEngine.engineVersion,
          active.packageVersion,
          now,
          result.grossAmountMinor,
          result.discountAmountMinor,
          result.payableAmountMinor,
          now,
        ],
      );
      for (final entry in [
        ...result.lineDiscounts.entries,
      ]..sort((a, b) => byId[a.key]!.lineNo.compareTo(byId[b.key]!.lineNo))) {
        final source = byId[entry.key]!;
        final gross = source.quantity.multiplyMinorHalfUp(
          source.unitPriceMinor,
        );
        database.database.execute(
          'INSERT INTO local_promotion_quote_line(quote_line_id,tenant_id,quote_id,source_line_id,line_no,sku_id,quantity_decimal,unit_price_minor,gross_amount_minor,discount_amount_minor,payable_amount_minor) VALUES(?,?,?,?,?,?,?,?,?,?,?)',
          [
            ulids.next(),
            binding.tenantId,
            quoteId,
            source.lineId,
            source.lineNo,
            source.skuId,
            _decimal(source.quantity),
            source.unitPriceMinor,
            gross,
            entry.value,
            gross - entry.value,
          ],
        );
      }
      var ordinal = 0;
      for (final adjustment in result.adjustments) {
        for (final allocation in adjustment.lineAllocations.entries) {
          database.database.execute(
            'INSERT INTO local_promotion_adjustment(adjustment_id,tenant_id,quote_id,source_line_id,source_type,source_id,calculation_stage,amount_minor,explanation_code,applied_flag,ordinal_no) VALUES(?,?,?,?,\'RULE\',?,\'PROMOTION\',?,\'APPLIED\',1,?)',
            [
              ulids.next(),
              binding.tenantId,
              quoteId,
              allocation.key,
              adjustment.sourceId,
              allocation.value,
              ++ordinal,
            ],
          );
        }
      }
      for (final explanation in result.explanations.where(
        (value) => value.code != 'APPLIED',
      )) {
        database.database.execute(
          'INSERT INTO local_promotion_adjustment(adjustment_id,tenant_id,quote_id,source_type,source_id,calculation_stage,amount_minor,explanation_code,applied_flag,ordinal_no) VALUES(?,?,?,\'RULE\',?,\'PROMOTION\',0,?,0,?)',
          [
            ulids.next(),
            binding.tenantId,
            quoteId,
            explanation.sourceId,
            explanation.code,
            ++ordinal,
          ],
        );
      }
      if (memberContext != null) {
        final binding = memberContext;
        database.database.execute(
          '''INSERT INTO local_promotion_quote_member_benefit(tenant_id,quote_id,entitlement_snapshot_id,
            benefit_version_id,selected_path,member_price_versions_json,capability_config_version,
            capability_sha256,rights_digest,explanation_sha256,package_version,package_sha256,
            content_sha256,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
          [
            database.binding.tenantId,
            quoteId,
            binding.entitlement.entitlementSnapshotId,
            binding.entitlement.benefitVersionId,
            binding.memberQuote.selectedPath,
            jsonEncode(binding.memberQuote.memberPriceVersions),
            binding.capabilityConfigVersion,
            binding.capabilitySha256,
            binding.entitlement.rightsDigest,
            binding.explanationSha256,
            binding.packageVersion,
            binding.packageSha256,
            binding.contentSha256,
            now,
          ],
        );
      }
      database.database.execute(
        'INSERT INTO local_audit_event(audit_id,tenant_id,action_code,aggregate_type,aggregate_id,actor_id,command_id,trace_id,after_status,amount_minor,currency,request_sha256,reason_code,occurred_at) VALUES(?,?,\'PROMOTION_QUOTE_CREATED\',\'PROMOTION_QUOTE\',?,?,?,?,\'CALCULATED\',?,\'CNY\',?,\'OFFLINE_RULE_PACKAGE\',?)',
        [
          ulids.next(),
          binding.tenantId,
          quoteId,
          binding.cashierId,
          pricingRequestId,
          pricingRequestId,
          result.discountAmountMinor,
          requestHash,
          now,
        ],
      );
    });
    return LocalPromotionQuoteResult(
      quoteId: quoteId,
      quoteFingerprint: fingerprint,
      packageVersion: active.packageVersion,
      quote: result,
      sourceAllocationsByLine: _sources(result),
      memberBenefitSnapshot: memberContext == null
          ? null
          : _settlementSnapshot(memberContext),
    );
  }

  Future<_MemberQuoteContext?> _memberContext({
    required MemberCacheView? member,
    required DateTime businessTime,
    required List<PromotionLine> lines,
    required PromotionQuote normal,
    required int capabilityConfigVersion,
    required String? capabilitySha256,
    required Map<String, String> unitIdsByLine,
  }) async {
    if (member == null ||
        member.levelCode == null ||
        member.entitlementSnapshotId == null) {
      return null;
    }
    if (memberBenefitPackageInstaller == null ||
        memberBenefitEngine == null ||
        capabilityConfigVersion <= 0 ||
        capabilitySha256 == null ||
        !RegExp(r'^[a-f0-9]{64}$').hasMatch(capabilitySha256)) {
      throw StateError(
        'MBP-QUOTE-001: member benefit capability is not safely configured',
      );
    }
    final active = await memberBenefitPackageInstaller!.requireActive();
    final at = businessTime.toUtc().toIso8601String();
    final benefitRows = database.database.select(
      '''SELECT * FROM local_member_benefit_level WHERE tenant_id=? AND store_id=? AND package_version=?
        AND level_code=? AND effective_at<=? AND (expires_at IS NULL OR expires_at>?)
        ORDER BY benefit_version_id''',
      [
        database.binding.tenantId,
        database.binding.storeId,
        active.packageVersion,
        member.levelCode,
        at,
        at,
      ],
    );
    if (benefitRows.length != 1) {
      throw StateError('MBP-QUOTE-002: entitlement unavailable or ambiguous');
    }
    final row = benefitRows.single;
    final entitlement = MemberBenefitEntitlement(
      entitlementSnapshotId: member.entitlementSnapshotId!,
      benefitVersionId: row['benefit_version_id']! as String,
      levelCode: member.levelCode!,
      memberPriceEligible: row['member_price_eligible'] == 1,
      stackingAllowed: row['stacking_allowed'] == 1,
      policyAllowStacking: row['policy_allow_stacking'] == 1,
      defaultCombinationPolicy: row['default_combination_policy']! as String,
      revocationEpoch: row['revocation_epoch']! as int,
      rightsDigest: member.rightsDigest,
    );
    final inputs = <MemberBenefitLineInput>[];
    for (final line in lines) {
      final unitId = unitIdsByLine[line.lineId];
      if (unitId == null || !RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(unitId)) {
        throw StateError('MBP-QUOTE-003: frozen sale unit is missing');
      }
      final prices = database.database.select(
        '''SELECT version_id,amount_minor FROM local_member_price_item
          WHERE tenant_id=? AND store_id=? AND package_version=? AND level_code=? AND sku_id=?
            AND unit_id=? AND effective_at<=? AND (expires_at IS NULL OR expires_at>?)
          ORDER BY CASE WHEN scope_store_id=? THEN 0 ELSE 1 END,version_no DESC,version_id LIMIT 1''',
        [
          database.binding.tenantId,
          database.binding.storeId,
          active.packageVersion,
          member.levelCode,
          line.skuId,
          unitId,
          at,
          at,
          database.binding.storeId,
        ],
      );
      inputs.add(
        MemberBenefitLineInput(
          line: line,
          normalDiscountMinor: normal.lineDiscounts[line.lineId] ?? 0,
          memberPriceMinor: prices.isEmpty
              ? null
              : prices.single['amount_minor']! as int,
          memberPriceVersionId: prices.isEmpty
              ? null
              : prices.single['version_id']! as String,
        ),
      );
    }
    final memberQuote = memberBenefitEngine!.combine(
      capabilityEnabled: true,
      entitlement: entitlement,
      lines: inputs,
    );
    final adjustments = <PromotionAdjustment>[];
    if (memberQuote.selectedPath != 'MEMBER_PATH') {
      adjustments.addAll(normal.adjustments);
    }
    final memberAllocations = <String, int>{};
    for (final entry in memberQuote.sourceAllocationsByLine.entries) {
      final amount = entry.value['MEMBER_PRICE'] ?? 0;
      if (amount > 0) memberAllocations[entry.key] = amount;
    }
    if (memberAllocations.isNotEmpty) {
      adjustments.add(
        PromotionAdjustment(
          'MEMBER_PRICE',
          memberAllocations.values.fold(0, (a, b) => a + b),
          Map.unmodifiable(memberAllocations),
        ),
      );
    }
    final quote = PromotionQuote(
      grossAmountMinor: memberQuote.grossAmountMinor,
      discountAmountMinor: memberQuote.discountAmountMinor,
      payableAmountMinor: memberQuote.payableAmountMinor,
      lineDiscounts: memberQuote.lineDiscounts,
      appliedRuleIds: [
        if (memberQuote.selectedPath != 'MEMBER_PATH') ...normal.appliedRuleIds,
        if (memberAllocations.isNotEmpty) 'MEMBER_PRICE',
      ],
      explanations: [
        if (memberQuote.selectedPath != 'MEMBER_PATH') ...normal.explanations,
        ...memberQuote.explanations.map(
          (code) => PromotionExplanation(entitlement.benefitVersionId, code),
        ),
      ],
      adjustments: adjustments,
    );
    final explanationSha256 = _hash(memberQuote.explanations);
    final contentSha256 = _hash({
      'entitlementSnapshotId': entitlement.entitlementSnapshotId,
      'benefitVersionId': entitlement.benefitVersionId,
      'selectedPath': memberQuote.selectedPath,
      'memberPriceVersions': memberQuote.memberPriceVersions,
      'capabilityConfigVersion': capabilityConfigVersion,
      'capabilitySha256': capabilitySha256,
      'rightsDigest': entitlement.rightsDigest,
      'explanationSha256': explanationSha256,
      'packageVersion': active.packageVersion,
      'packageSha256': active.payloadSha256,
    });
    return _MemberQuoteContext(
      quote: quote,
      memberQuote: memberQuote,
      entitlement: entitlement,
      packageVersion: active.packageVersion,
      packageSha256: active.payloadSha256,
      explanationSha256: explanationSha256,
      contentSha256: contentSha256,
      capabilityConfigVersion: capabilityConfigVersion,
      capabilitySha256: capabilitySha256,
    );
  }

  LocalPromotionQuoteResult _read(String quoteId, {required bool duplicate}) {
    final row = database.database.select(
      'SELECT * FROM local_promotion_quote WHERE tenant_id=? AND quote_id=?',
      [database.binding.tenantId, quoteId],
    ).single;
    final lineRows = database.database.select(
      'SELECT source_line_id,discount_amount_minor FROM local_promotion_quote_line WHERE tenant_id=? AND quote_id=? ORDER BY line_no',
      [database.binding.tenantId, quoteId],
    );
    final adjustments = database.database.select(
      'SELECT source_line_id,source_id,amount_minor FROM local_promotion_adjustment WHERE tenant_id=? AND quote_id=? AND applied_flag=1 ORDER BY ordinal_no',
      [database.binding.tenantId, quoteId],
    );
    final sources = <String, Map<String, int>>{};
    for (final item in adjustments) {
      (sources[item['source_line_id']! as String] ??=
              {})['RULE:${item['source_id']}'] =
          item['amount_minor']! as int;
    }
    final quote = PromotionQuote(
      grossAmountMinor: row['gross_amount_minor']! as int,
      discountAmountMinor: row['discount_amount_minor']! as int,
      payableAmountMinor: row['payable_amount_minor']! as int,
      lineDiscounts: {
        for (final line in lineRows)
          line['source_line_id']! as String:
              line['discount_amount_minor']! as int,
      },
      appliedRuleIds: adjustments
          .map((item) => item['source_id']! as String)
          .toSet()
          .toList(),
      explanations: const [],
      adjustments: const [],
    );
    final memberBindings = database.database.select(
      'SELECT * FROM local_promotion_quote_member_benefit WHERE tenant_id=? AND quote_id=?',
      [database.binding.tenantId, quoteId],
    );
    return LocalPromotionQuoteResult(
      quoteId: quoteId,
      quoteFingerprint: row['result_sha256']! as String,
      packageVersion: row['package_version']! as int,
      quote: quote,
      sourceAllocationsByLine: sources,
      memberBenefitSnapshot: memberBindings.isEmpty
          ? null
          : _snapshotFromRow(memberBindings.single),
      duplicate: duplicate,
    );
  }

  MemberBenefitSettlementSnapshot _settlementSnapshot(
    _MemberQuoteContext value,
  ) => MemberBenefitSettlementSnapshot(
    entitlementSnapshotId: value.entitlement.entitlementSnapshotId,
    benefitVersionId: value.entitlement.benefitVersionId,
    selectedPath: value.memberQuote.selectedPath,
    memberPriceVersions: value.memberQuote.memberPriceVersions,
    capabilityConfigVersion: value.capabilityConfigVersion,
    capabilitySha256: value.capabilitySha256,
    rightsDigest: value.entitlement.rightsDigest,
    explanationSha256: value.explanationSha256,
    packageVersion: value.packageVersion,
    packageSha256: value.packageSha256,
    contentSha256: value.contentSha256,
  );

  MemberBenefitSettlementSnapshot _snapshotFromRow(dynamic row) =>
      MemberBenefitSettlementSnapshot(
        entitlementSnapshotId: row['entitlement_snapshot_id']! as String,
        benefitVersionId: row['benefit_version_id']! as String,
        selectedPath: row['selected_path']! as String,
        memberPriceVersions: (jsonDecode(
          row['member_price_versions_json']! as String,
        ) as List).cast<String>(),
        capabilityConfigVersion: row['capability_config_version']! as int,
        capabilitySha256: row['capability_sha256']! as String,
        rightsDigest: row['rights_digest']! as String,
        explanationSha256: row['explanation_sha256']! as String,
        packageVersion: row['package_version']! as int,
        packageSha256: row['package_sha256']! as String,
        contentSha256: row['content_sha256']! as String,
      );

  Map<String, Map<String, int>> _sources(PromotionQuote quote) {
    final result = <String, Map<String, int>>{};
    for (final adjustment in quote.adjustments) {
      for (final allocation in adjustment.lineAllocations.entries) {
        (result[allocation.key] ??= {})['RULE:${adjustment.sourceId}'] =
            allocation.value;
      }
    }
    return result;
  }

  String _resultHash(PromotionQuote value) => _hash({
    'engineVersion': PromotionEngine.engineVersion,
    'grossAmountMinor': value.grossAmountMinor,
    'discountAmountMinor': value.discountAmountMinor,
    'payableAmountMinor': value.payableAmountMinor,
    'lineDiscounts': value.lineDiscounts,
    'appliedRuleIds': value.appliedRuleIds,
  });

  String _hash(Object value) => sha256
      .convert(utf8.encode(PromotedOrderSnapshotCodec.canonicalJson(value)))
      .toString();

  String _decimal(ExactDecimal value) {
    final digits = value.unscaled.toString().padLeft(value.scale + 1, '0');
    if (value.scale == 0) return digits;
    return '${digits.substring(0, digits.length - value.scale)}.${digits.substring(digits.length - value.scale)}';
  }
}

final class _MemberQuoteContext {
  const _MemberQuoteContext({
    required this.quote,
    required this.memberQuote,
    required this.entitlement,
    required this.packageVersion,
    required this.packageSha256,
    required this.explanationSha256,
    required this.contentSha256,
    required this.capabilityConfigVersion,
    required this.capabilitySha256,
  });
  final PromotionQuote quote;
  final MemberBenefitQuote memberQuote;
  final MemberBenefitEntitlement entitlement;
  final int packageVersion;
  final String packageSha256;
  final String explanationSha256;
  final String contentSha256;
  final int capabilityConfigVersion;
  final String capabilitySha256;
}
