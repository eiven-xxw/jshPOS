import 'dart:convert';

import 'package:crypto/crypto.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../checkout/domain/promoted_order_snapshot_codec.dart';
import '../../checkout/domain/ulid_generator.dart';
import '../domain/promotion_engine.dart';
import '../infrastructure/promotion_package_installer.dart';

/// POS 正式离线询价结果；报价身份、指纹和逐行来源分摊随后进入成交快照。
final class LocalPromotionQuoteResult {
  const LocalPromotionQuoteResult({
    required this.quoteId,
    required this.quoteFingerprint,
    required this.packageVersion,
    required this.quote,
    required this.sourceAllocationsByLine,
    this.duplicate = false,
  });

  final String quoteId;
  final String quoteFingerprint;
  final int packageVersion;
  final PromotionQuote quote;
  final Map<String, Map<String, int>> sourceAllocationsByLine;
  final bool duplicate;
}

/// 使用已验签活动规则包计算并原子保存不可变报价，不允许 UI 直接写 SQLite。
final class LocalPromotionQuoteService {
  LocalPromotionQuoteService({
    required this.database,
    required this.packageInstaller,
    required this.engine,
    required this.ulids,
  });

  final PosLocalDatabase database;
  final PromotionPackageInstaller packageInstaller;
  final PromotionEngine engine;
  final UlidGenerator ulids;

  Future<LocalPromotionQuoteResult> quote({
    required String pricingRequestId,
    required DateTime businessTime,
    required String channel,
    required List<PromotionLine> lines,
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
    final result = engine.quote(
      businessTime: businessTime,
      storeId: binding.storeId,
      channel: channel,
      lines: lines,
      rules: active.rules,
    );
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
    return LocalPromotionQuoteResult(
      quoteId: quoteId,
      quoteFingerprint: row['result_sha256']! as String,
      packageVersion: row['package_version']! as int,
      quote: quote,
      sourceAllocationsByLine: sources,
      duplicate: duplicate,
    );
  }

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
