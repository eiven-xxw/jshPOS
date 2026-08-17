import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../domain/promotion_engine.dart';

/// 从受控下载通道取得的载荷、摘要、签名与可信密钥版本标识。
final class PromotionPackageEnvelope {
  const PromotionPackageEnvelope({
    required this.payload,
    required this.payloadSha256,
    required this.signature,
    required this.signingKeyId,
  });

  final Uint8List payload;
  final String payloadSha256;
  final Uint8List signature;
  final String signingKeyId;
}

/// 已通过可信密钥、设备绑定、能力和有效期校验并安装的促销包。
final class InstalledPromotionPackage {
  const InstalledPromotionPackage({
    required this.slot,
    required this.packageVersion,
    required this.payloadSha256,
    required this.expiresAt,
    required this.rules,
    required this.manualPolicy,
  });
  final String slot;
  final int packageVersion;
  final String payloadSha256;
  final DateTime expiresAt;
  final List<PromotionRule> rules;
  final ManualPromotionPolicy manualPolicy;
}

/// 随签名包冻结的 PRM-002 人工优惠权限与金额阈值，不从客户端输入构造。
final class ManualPromotionPolicy {
  const ManualPromotionPolicy({
    required this.policyVersionId,
    required this.policySha256,
    required this.withoutApprovalMinor,
    required this.withApprovalMinor,
    required this.minimumLinePayableMinor,
    required this.maximumRoundingMinor,
    required this.roundingMultiplesMinor,
    required this.canonicalJson,
  });

  final int policyVersionId;
  final String policySha256;
  final int withoutApprovalMinor;
  final int withApprovalMinor;
  final int minimumLinePayableMinor;
  final int maximumRoundingMinor;
  final List<int> roundingMultiplesMinor;
  final String canonicalJson;
}

/// 使用应用预置可信公钥验证并原子切换 SQLite A/B 槽的规则包安装器。
final class PromotionPackageInstaller {
  PromotionPackageInstaller(
    this.database, {
    required Map<String, SimplePublicKey> trustedSigningKeys,
    Ed25519? algorithm,
    DateTime Function()? utcNow,
  }) : _trustedSigningKeys = Map.unmodifiable(trustedSigningKeys),
       _algorithm = algorithm ?? Ed25519(),
       _utcNow = utcNow ?? (() => DateTime.now().toUtc());

  final PosLocalDatabase database;
  final Ed25519 _algorithm;
  final Map<String, SimplePublicKey> _trustedSigningKeys;
  final DateTime Function() _utcNow;

  Future<InstalledPromotionPackage> install(
    PromotionPackageEnvelope envelope,
  ) async {
    final actualHash = sha256.convert(envelope.payload).toString();
    if (!_constantTimeEquals(actualHash, envelope.payloadSha256)) {
      throw StateError('PRM-PKG-101: package digest mismatch');
    }
    final trustedKey = _trustedSigningKeys[envelope.signingKeyId];
    if (trustedKey == null) {
      throw StateError('PRM-PKG-109: signing key is not trusted');
    }
    final verified = await _algorithm.verify(
      envelope.payload,
      signature: Signature(envelope.signature, publicKey: trustedKey),
    );
    if (!verified) {
      throw StateError('PRM-PKG-102: signature verification failed');
    }
    final metadata = _parseAndValidate(envelope.payload);
    final binding = database.binding;
    if (metadata.tenantId != binding.tenantId ||
        metadata.storeId != binding.storeId) {
      throw StateError('PRM-PKG-103: tenant or store binding mismatch');
    }
    final current = database.database.select(
      'SELECT active_slot,active_package_version FROM local_promotion_package_binding WHERE singleton_id=1 AND tenant_id=? AND store_id=?',
      [binding.tenantId, binding.storeId],
    );
    final currentVersion = current.isEmpty
        ? 0
        : current.single['active_package_version']! as int;
    if (metadata.previousVersion != currentVersion ||
        metadata.packageVersion != currentVersion + 1) {
      throw StateError('PRM-PKG-104: package version is not contiguous');
    }
    final activeSlot = current.isEmpty
        ? null
        : current.single['active_slot']! as String;
    final targetSlot = activeSlot == 'A' ? 'B' : 'A';
    final now = _utcNow().toUtc().toIso8601String();
    database.transaction(() {
      database.database.execute(
        '''INSERT INTO local_promotion_package_slot(slot_code,tenant_id,store_id,package_version,previous_version,
           schema_version,engine_version,payload_blob,payload_sha256,signature_blob,signing_key_id,generated_at,
           expires_at,installed_at,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
           ON CONFLICT(tenant_id,store_id,slot_code) DO UPDATE SET
             package_version=excluded.package_version,previous_version=excluded.previous_version,
             schema_version=excluded.schema_version,engine_version=excluded.engine_version,
             payload_blob=excluded.payload_blob,payload_sha256=excluded.payload_sha256,
             signature_blob=excluded.signature_blob,signing_key_id=excluded.signing_key_id,
             generated_at=excluded.generated_at,expires_at=excluded.expires_at,
             installed_at=excluded.installed_at,state='STAGED' ''',
        [
          targetSlot,
          binding.tenantId,
          binding.storeId,
          metadata.packageVersion,
          metadata.previousVersion,
          metadata.schemaVersion,
          metadata.engineVersion,
          envelope.payload,
          actualHash,
          envelope.signature,
          envelope.signingKeyId,
          metadata.generatedAt.toUtc().toIso8601String(),
          metadata.expiresAt.toUtc().toIso8601String(),
          now,
          'STAGED',
        ],
      );
      _installManualPolicy(metadata, now);
      if (activeSlot != null) {
        database.database.execute(
          "UPDATE local_promotion_package_slot SET state='RETIRED' WHERE tenant_id=? AND store_id=? AND slot_code=? AND state='ACTIVE'",
          [binding.tenantId, binding.storeId, activeSlot],
        );
      }
      database.database.execute(
        '''INSERT INTO local_promotion_package_binding(singleton_id,tenant_id,store_id,active_slot,
           active_package_version,active_payload_sha256,switched_at,record_version)
           VALUES(1,?,?,?,?,?,?,1)
           ON CONFLICT(singleton_id) DO UPDATE SET active_slot=excluded.active_slot,
             active_package_version=excluded.active_package_version,
             active_payload_sha256=excluded.active_payload_sha256,switched_at=excluded.switched_at,
             record_version=local_promotion_package_binding.record_version+1''',
        [
          binding.tenantId,
          binding.storeId,
          targetSlot,
          metadata.packageVersion,
          actualHash,
          now,
        ],
      );
      database.database.execute(
        "UPDATE local_promotion_package_slot SET state='ACTIVE' WHERE tenant_id=? AND store_id=? AND slot_code=? AND payload_sha256=? AND state='STAGED'",
        [binding.tenantId, binding.storeId, targetSlot, actualHash],
      );
      database.checkpoint('promotion_package_after_atomic_switch');
    });
    return InstalledPromotionPackage(
      slot: targetSlot,
      packageVersion: metadata.packageVersion,
      payloadSha256: actualHash,
      expiresAt: metadata.expiresAt,
      rules: List.unmodifiable(metadata.rules),
      manualPolicy: metadata.manualPolicy,
    );
  }

  /// 每次交易前重新校验活动槽摘要、签名、绑定和有效期，防止本地篡改或过期规则被静默使用。
  Future<InstalledPromotionPackage> requireActive() async {
    final binding = database.binding;
    final rows = database.database.select(
      '''SELECT s.slot_code,s.package_version,s.payload_blob,s.payload_sha256,s.signature_blob,
         s.signing_key_id,s.expires_at,b.active_payload_sha256
         FROM local_promotion_package_binding b
         JOIN local_promotion_package_slot s ON s.tenant_id=b.tenant_id AND s.store_id=b.store_id
          AND s.slot_code=b.active_slot AND s.state='ACTIVE'
         WHERE b.singleton_id=1 AND b.tenant_id=? AND b.store_id=?''',
      [binding.tenantId, binding.storeId],
    );
    if (rows.length != 1) {
      throw StateError('PRM-PKG-110: active package is missing');
    }
    final row = rows.single;
    final payload = Uint8List.fromList(row['payload_blob']! as List<int>);
    final signature = Uint8List.fromList(row['signature_blob']! as List<int>);
    final digest = sha256.convert(payload).toString();
    if (!_constantTimeEquals(digest, row['payload_sha256']! as String) ||
        !_constantTimeEquals(digest, row['active_payload_sha256']! as String)) {
      throw StateError('PRM-PKG-111: active package digest mismatch');
    }
    final keyId = row['signing_key_id']! as String;
    final trustedKey = _trustedSigningKeys[keyId];
    if (trustedKey == null ||
        !await _algorithm.verify(
          payload,
          signature: Signature(signature, publicKey: trustedKey),
        )) {
      throw StateError('PRM-PKG-112: active package signature invalid');
    }
    final metadata = _parseAndValidate(payload);
    if (metadata.tenantId != binding.tenantId ||
        metadata.storeId != binding.storeId ||
        metadata.packageVersion != row['package_version']) {
      throw StateError('PRM-PKG-113: active package binding mismatch');
    }
    return InstalledPromotionPackage(
      slot: row['slot_code']! as String,
      packageVersion: metadata.packageVersion,
      payloadSha256: digest,
      expiresAt: metadata.expiresAt,
      rules: List.unmodifiable(metadata.rules),
      manualPolicy: metadata.manualPolicy,
    );
  }

  void _installManualPolicy(_PackageMetadata metadata, String installedAt) {
    final binding = database.binding;
    final policy = metadata.manualPolicy;
    database.database.execute(
      '''INSERT INTO local_promotion_manual_policy(
        tenant_id,store_id,package_version,policy_version_id,policy_sha256,
        without_approval_minor,with_approval_minor,minimum_line_payable_minor,
        maximum_rounding_minor,rounding_multiples_json,installed_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(tenant_id,store_id,package_version,policy_version_id) DO NOTHING''',
      [
        binding.tenantId,
        binding.storeId,
        metadata.packageVersion,
        policy.policyVersionId,
        policy.policySha256,
        policy.withoutApprovalMinor,
        policy.withApprovalMinor,
        policy.minimumLinePayableMinor,
        policy.maximumRoundingMinor,
        jsonEncode(policy.roundingMultiplesMinor),
        installedAt,
      ],
    );
    final rows = database.database.select(
      '''SELECT * FROM local_promotion_manual_policy
         WHERE tenant_id=? AND store_id=? AND package_version=? AND policy_version_id=?''',
      [
        binding.tenantId,
        binding.storeId,
        metadata.packageVersion,
        policy.policyVersionId,
      ],
    );
    if (rows.length != 1 ||
        rows.single['policy_sha256'] != policy.policySha256 ||
        rows.single['without_approval_minor'] != policy.withoutApprovalMinor ||
        rows.single['with_approval_minor'] != policy.withApprovalMinor ||
        rows.single['minimum_line_payable_minor'] !=
            policy.minimumLinePayableMinor ||
        rows.single['maximum_rounding_minor'] != policy.maximumRoundingMinor ||
        rows.single['rounding_multiples_json'] !=
            jsonEncode(policy.roundingMultiplesMinor)) {
      throw StateError('PRM-PKG-114: manual policy persistence mismatch');
    }
  }

  _PackageMetadata _parseAndValidate(Uint8List payload) {
    final text = utf8.decode(payload, allowMalformed: false);
    final lines = const LineSplitter().convert(text);
    if (lines.isEmpty) throw StateError('PRM-PKG-105: empty package');
    final header = lines.first.split('|');
    if (header.length != 9 ||
        header[0] != 'JSHPRM' ||
        header[1] != '1.0' ||
        header[2] != PromotionEngine.engineVersion) {
      throw StateError('PRM-PKG-106: unsupported package capability');
    }
    final version = int.tryParse(header[5]);
    final previous = int.tryParse(header[6]);
    final generated = DateTime.tryParse(header[7]);
    final expires = DateTime.tryParse(header[8]);
    if (version == null ||
        previous == null ||
        version <= 0 ||
        previous < 0 ||
        previous >= version ||
        generated == null ||
        expires == null ||
        !expires.isAfter(generated) ||
        !expires.isAfter(_utcNow().toUtc())) {
      throw StateError('PRM-PKG-107: invalid package metadata or expiry');
    }
    final forbidden = RegExp(
      r'COUPON|MEMBER|LOYALTY|STORED_VALUE|BUDGET_RESERVATION|PROVIDER_NETWORK',
    );
    if (lines.length - 1 > 5000) {
      throw StateError('PRM-PKG-108: package rule limit exceeded');
    }
    final rules = <PromotionRule>[];
    String? previousRuleId;
    ManualPromotionPolicy? manualPolicy;
    for (final row in lines.skip(1)) {
      if (row.isEmpty) continue;
      if (row.startsWith('@MANUAL_POLICY|')) {
        if (manualPolicy != null) {
          throw StateError('PRM-PKG-115: duplicate manual policy record');
        }
        manualPolicy = _decodeManualPolicy(row);
        continue;
      }
      final separator = row.indexOf('|');
      final ruleId = separator < 0 ? '' : row.substring(0, separator);
      if (separator != 26 ||
          !RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(ruleId) ||
          (previousRuleId != null && ruleId.compareTo(previousRuleId) <= 0) ||
          forbidden.hasMatch(row)) {
        throw StateError('PRM-PKG-108: unsupported or malformed rule record');
      }
      try {
        final rule = _decodeRule(_unescape(row.substring(separator + 1)));
        if (rule.ruleVersionId != ruleId) {
          throw const FormatException('rule identity mismatch');
        }
        PromotionEngine().validateRule(rule);
        rules.add(rule);
        previousRuleId = ruleId;
      } on Object catch (error) {
        throw StateError('PRM-PKG-108: malformed rule AST: $error');
      }
    }
    if (manualPolicy == null) {
      throw StateError('PRM-PKG-116: signed manual policy is missing');
    }
    return _PackageMetadata(
      schemaVersion: header[1],
      engineVersion: header[2],
      tenantId: header[3],
      storeId: header[4],
      packageVersion: version,
      previousVersion: previous,
      generatedAt: generated,
      expiresAt: expires,
      rules: rules,
      manualPolicy: manualPolicy,
    );
  }

  ManualPromotionPolicy _decodeManualPolicy(String row) {
    final match = RegExp(
      r'^@MANUAL_POLICY\|([1-9][0-9]*)\|([a-f0-9]{64})\|(.*)$',
    ).firstMatch(row);
    if (match == null) {
      throw StateError('PRM-PKG-117: malformed manual policy record');
    }
    final version = int.parse(match.group(1)!);
    final expectedHash = match.group(2)!;
    final canonicalJson = _unescape(match.group(3)!);
    final actualHash = sha256.convert(utf8.encode(canonicalJson)).toString();
    if (!_constantTimeEquals(actualHash, expectedHash)) {
      throw StateError('PRM-PKG-118: manual policy digest mismatch');
    }
    final content = jsonDecode(canonicalJson) as Map<String, Object?>;
    const fields = {
      'policyType',
      'withoutApprovalMinor',
      'withApprovalMinor',
      'minimumLinePayableMinor',
      'maximumRoundingMinor',
      'roundingMultiplesMinor',
    };
    if (content.keys.toSet().difference(fields).isNotEmpty ||
        fields.difference(content.keys.toSet()).isNotEmpty ||
        content['policyType'] != 'PROMOTION_MANUAL_AUTHORITY') {
      throw StateError('PRM-PKG-119: unsupported manual policy structure');
    }
    int integer(String key) {
      final value = content[key];
      if (value is! int) {
        throw StateError('PRM-PKG-120: manual policy integer required');
      }
      return value;
    }

    final multiplesValue = content['roundingMultiplesMinor'];
    if (multiplesValue is! List<Object?> ||
        multiplesValue.any((value) => value is! int)) {
      throw StateError('PRM-PKG-121: manual policy multiples invalid');
    }
    final multiples = multiplesValue.cast<int>();
    final withoutApproval = integer('withoutApprovalMinor');
    final withApproval = integer('withApprovalMinor');
    final minimumLinePayable = integer('minimumLinePayableMinor');
    final maximumRounding = integer('maximumRoundingMinor');
    if (withoutApproval < 0 ||
        withApproval < withoutApproval ||
        minimumLinePayable < 0 ||
        maximumRounding < 0 ||
        maximumRounding > withApproval ||
        multiples.isEmpty ||
        multiples.any((value) => value <= 0 || value > 10000)) {
      throw StateError('PRM-PKG-122: manual policy bounds invalid');
    }
    return ManualPromotionPolicy(
      policyVersionId: version,
      policySha256: expectedHash,
      withoutApprovalMinor: withoutApproval,
      withApprovalMinor: withApproval,
      minimumLinePayableMinor: minimumLinePayable,
      maximumRoundingMinor: maximumRounding,
      roundingMultiplesMinor: List.unmodifiable(multiples),
      canonicalJson: canonicalJson,
    );
  }

  PromotionRule _decodeRule(String canonicalJson) {
    final value = jsonDecode(canonicalJson) as Map<String, Object?>;
    final scope = (value['scope'] as Map<String, Object?>?) ?? const {};
    final benefit = (value['benefit'] as Map<String, Object?>?) ?? const {};
    final type = PromotionRuleType.values.firstWhere(
      (item) => _enumName(item) == value['ruleType'],
    );
    final stackMode = PromotionStackMode.values.firstWhere(
      (item) => _enumName(item) == value['stackMode'],
    );
    return PromotionRule(
      ruleVersionId: value['ruleVersionId']! as String,
      ruleType: type,
      priority: value['priority']! as int,
      stackMode: stackMode,
      exclusiveGroup: value['exclusiveGroup'] as String?,
      effectiveFrom: DateTime.parse(value['effectiveFrom']! as String),
      effectiveTo: value['effectiveTo'] == null
          ? null
          : DateTime.parse(value['effectiveTo']! as String),
      scope: PromotionScope(
        skuIds: _stringSet(scope['skuIds']),
        categoryIds: _stringSet(scope['categoryIds']),
        brandIds: _stringSet(scope['brandIds']),
        storeIds: _stringSet(scope['storeIds']),
        channels: _stringSet(scope['channels']),
        businessDays: _intSet(scope['businessDays']),
      ),
      benefit: PromotionBenefit(
        amountMinor: benefit['amountMinor'] as int?,
        discountRate: _decimal(benefit['discountRate'], 8),
        nth: benefit['nth'] as int?,
        thresholdMinor: benefit['thresholdMinor'] as int?,
        thresholdQuantity: _decimal(benefit['thresholdQuantity'], 6),
        bundlePriceMinor: benefit['bundlePriceMinor'] as int?,
        bundleComponents:
            ((benefit['bundleComponents'] as List<Object?>?) ?? const []).map((
              item,
            ) {
              final component = item! as Map<String, Object?>;
              return BundleComponent(
                component['skuId']! as String,
                ExactDecimal.parse(
                  component['quantity']! as String,
                  maximumScale: 6,
                ),
              );
            }).toList(),
      ),
    );
  }

  String _unescape(String value) {
    final result = StringBuffer();
    for (var index = 0; index < value.length; index++) {
      final current = value[index];
      if (current != r'\') {
        result.write(current);
        continue;
      }
      if (++index >= value.length) {
        throw const FormatException('dangling package escape');
      }
      switch (value[index]) {
        case r'\':
          result.write(r'\');
          break;
        case 'p':
          result.write('|');
          break;
        case 'r':
          result.write('\r');
          break;
        case 'n':
          result.write('\n');
          break;
        default:
          throw const FormatException('unknown package escape');
      }
    }
    return result.toString();
  }

  String _enumName(Object value) => switch (value) {
    PromotionRuleType.specialPrice => 'SPECIAL_PRICE',
    PromotionRuleType.percentOff => 'PERCENT_OFF',
    PromotionRuleType.amountOff => 'AMOUNT_OFF',
    PromotionRuleType.nthItemDiscount => 'NTH_ITEM_DISCOUNT',
    PromotionRuleType.bundlePrice => 'BUNDLE_PRICE',
    PromotionRuleType.thresholdAmountOff => 'THRESHOLD_AMOUNT_OFF',
    PromotionRuleType.thresholdQuantityOff => 'THRESHOLD_QUANTITY_OFF',
    PromotionStackMode.exclusive => 'EXCLUSIVE',
    PromotionStackMode.stackable => 'STACKABLE',
    PromotionStackMode.bestOfGroup => 'BEST_OF_GROUP',
    _ => throw const FormatException('unsupported enum'),
  };

  Set<String> _stringSet(Object? value) =>
      ((value as List<Object?>?) ?? const []).map((item) => '$item').toSet();
  Set<int> _intSet(Object? value) => ((value as List<Object?>?) ?? const [])
      .map((item) => int.parse('$item'))
      .toSet();
  ExactDecimal? _decimal(Object? value, int scale) =>
      value == null ? null : ExactDecimal.parse('$value', maximumScale: scale);

  bool _constantTimeEquals(String left, String right) {
    if (left.length != right.length) return false;
    var difference = 0;
    for (var index = 0; index < left.length; index++) {
      difference |= left.codeUnitAt(index) ^ right.codeUnitAt(index);
    }
    return difference == 0;
  }
}

final class _PackageMetadata {
  const _PackageMetadata({
    required this.schemaVersion,
    required this.engineVersion,
    required this.tenantId,
    required this.storeId,
    required this.packageVersion,
    required this.previousVersion,
    required this.generatedAt,
    required this.expiresAt,
    required this.rules,
    required this.manualPolicy,
  });
  final String schemaVersion;
  final String engineVersion;
  final String tenantId;
  final String storeId;
  final int packageVersion;
  final int previousVersion;
  final DateTime generatedAt;
  final DateTime expiresAt;
  final List<PromotionRule> rules;
  final ManualPromotionPolicy manualPolicy;
}
