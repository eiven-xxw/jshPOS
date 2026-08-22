import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../domain/lot_expiry.dart';

/// 服务端生成并签名的社区超市批次数据包；私钥不进入 POS。
final class LotPackageEnvelope {
  const LotPackageEnvelope({
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

final class InstalledLotPackage {
  const InstalledLotPackage({
    required this.packageVersion,
    required this.payloadSha256,
    required this.recordCount,
    this.duplicate = false,
  });

  final int packageVersion;
  final String payloadSha256;
  final int recordCount;
  final bool duplicate;
}

/// 校验签名、摘要、可信门店和连续版本后，原子安装策略及批次余额快照。
final class LotPackageInstaller {
  LotPackageInstaller(
    this.database, {
    required Map<String, SimplePublicKey> trustedSigningKeys,
    Ed25519? algorithm,
    DateTime Function()? utcNow,
  }) : _trustedSigningKeys = Map.unmodifiable(trustedSigningKeys),
       _algorithm = algorithm ?? Ed25519(),
       _utcNow = utcNow ?? (() => DateTime.now().toUtc());

  final PosLocalDatabase database;
  final Map<String, SimplePublicKey> _trustedSigningKeys;
  final Ed25519 _algorithm;
  final DateTime Function() _utcNow;

  Future<InstalledLotPackage> install(LotPackageEnvelope envelope) async {
    final digest = sha256.convert(envelope.payload).toString();
    if (!_constantTimeEquals(digest, envelope.payloadSha256)) {
      throw StateError('LOT-DPK-101: lot package digest mismatch');
    }
    final key = _trustedSigningKeys[envelope.signingKeyId];
    if (key == null ||
        !await _algorithm.verify(
          envelope.payload,
          signature: Signature(envelope.signature, publicKey: key),
        )) {
      throw StateError('LOT-DPK-102: lot package signature is untrusted');
    }
    final decoded = _decode(envelope.payload);
    final binding = database.binding;
    if (decoded.tenantId != binding.tenantId ||
        decoded.storeId != binding.storeId ||
        decoded.businessZoneId != binding.storeTimezone) {
      throw StateError(
        'LOT-DPK-103: tenant, store or timezone binding mismatch',
      );
    }
    final active = database.database.select(
      'SELECT tenant_id,store_id,active_package_version,active_payload_sha256 FROM local_lot_package_binding WHERE singleton_id=1',
    );
    if (active.isNotEmpty &&
        (active.single['tenant_id'] != binding.tenantId ||
            active.single['store_id'] != binding.storeId)) {
      throw StateError('LOT-DPK-103: local package binding is corrupted');
    }
    final activeVersion = active.isEmpty
        ? 0
        : active.single['active_package_version']! as int;
    if (decoded.packageVersion == activeVersion &&
        active.single['active_payload_sha256'] == digest) {
      return InstalledLotPackage(
        packageVersion: decoded.packageVersion,
        payloadSha256: digest,
        recordCount: decoded.policies.length + decoded.lots.length,
        duplicate: true,
      );
    }
    if (decoded.previousVersion != activeVersion ||
        decoded.packageVersion != activeVersion + 1) {
      throw StateError('LOT-DPK-104: old or non-contiguous lot package');
    }
    final pending =
        database.database.select(
              "SELECT COUNT(*) AS c FROM local_outbox WHERE tenant_id=? AND event_type='inventory.lot-sale.requested.v1' AND status<>'ACKED'",
              [binding.tenantId],
            ).single['c']!
            as int;
    if (pending > 0) {
      throw StateError('LOT-DPK-105: pending lot facts block package switch');
    }
    final now = _utcNow().toUtc().toIso8601String();
    database.transaction(() {
      database.database.execute(
        '''INSERT INTO local_lot_package_slot(package_id,tenant_id,store_id,warehouse_id,industry,
           industry_template_version_id,industry_template_sha256,business_zone_id,business_day_start,
           package_version,previous_version,schema_version,payload_sha256,signing_key_id,generated_at,
           installed_at,record_count,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'STAGED')''',
        [
          digest,
          decoded.tenantId,
          decoded.storeId,
          decoded.warehouseId,
          'COMMUNITY_SUPERMARKET',
          decoded.industryTemplateVersionId,
          decoded.industryTemplateSha256,
          decoded.businessZoneId,
          decoded.businessDayStart,
          decoded.packageVersion,
          decoded.previousVersion,
          '1.0',
          digest,
          envelope.signingKeyId,
          decoded.generatedAt.toUtc().toIso8601String(),
          now,
          decoded.policies.length + decoded.lots.length,
        ],
      );
      for (final policy in decoded.policies) {
        database.database.execute(
          '''INSERT INTO local_lot_policy(tenant_id,store_id,package_version,policy_version_id,sku_id,
             enabled,expiry_basis,shelf_life_days,near_expiry_days,effective_from,content_sha256)
             VALUES(?,?,?,?,?,?,?,?,?,?,?)''',
          [
            decoded.tenantId,
            decoded.storeId,
            decoded.packageVersion,
            policy.policyVersionId,
            policy.skuId,
            policy.enabled ? 1 : 0,
            policy.expiryBasis,
            policy.shelfLifeDays,
            policy.nearExpiryDays,
            policy.effectiveFrom.toUtc().toIso8601String(),
            policy.contentSha256,
          ],
        );
      }
      for (final lot in decoded.lots) {
        database.database.execute(
          '''INSERT INTO local_lot_balance(tenant_id,store_id,package_version,lot_id,warehouse_id,sku_id,
             base_unit_id,supplier_lot_code,internal_lot_code,production_date,received_date,expiry_date,
             policy_version_id,near_expiry_days,quantity_decimal,last_ledger_sequence,source_sha256,record_version)
             VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)''',
          [
            decoded.tenantId,
            decoded.storeId,
            decoded.packageVersion,
            lot.lotId,
            decoded.warehouseId,
            lot.skuId,
            lot.baseUnitId,
            lot.supplierLotCode,
            lot.internalLotCode,
            lot.productionDate,
            lot.receivedDate,
            lot.expiryDate,
            lot.policyVersionId,
            lot.nearExpiryDays,
            lot.quantity.canonical,
            lot.lastLedgerSequence,
            lot.sourceSha256,
          ],
        );
      }
      database.database.execute(
        "UPDATE local_lot_package_slot SET state='SUPERSEDED' WHERE tenant_id=? AND store_id=? AND state='ACTIVE'",
        [decoded.tenantId, decoded.storeId],
      );
      database.database.execute(
        '''INSERT INTO local_lot_package_binding(singleton_id,tenant_id,store_id,active_package_version,
           active_payload_sha256,activated_at) VALUES(1,?,?,?,?,?)
           ON CONFLICT(singleton_id) DO UPDATE SET active_package_version=excluded.active_package_version,
             active_payload_sha256=excluded.active_payload_sha256,activated_at=excluded.activated_at
           WHERE excluded.tenant_id=local_lot_package_binding.tenant_id
             AND excluded.store_id=local_lot_package_binding.store_id''',
        [
          decoded.tenantId,
          decoded.storeId,
          decoded.packageVersion,
          digest,
          now,
        ],
      );
      database.database.execute(
        "UPDATE local_lot_package_slot SET state='ACTIVE' WHERE tenant_id=? AND store_id=? AND package_version=? AND state='STAGED'",
        [decoded.tenantId, decoded.storeId, decoded.packageVersion],
      );
      database.checkpoint('lot_package_after_atomic_switch');
    });
    return InstalledLotPackage(
      packageVersion: decoded.packageVersion,
      payloadSha256: digest,
      recordCount: decoded.policies.length + decoded.lots.length,
    );
  }

  _DecodedLotPackage _decode(Uint8List payload) {
    final value = jsonDecode(utf8.decode(payload, allowMalformed: false));
    if (value is! Map<String, Object?>) {
      throw StateError('LOT-DPK-106: lot package is not an object');
    }
    const required = {
      'schemaVersion',
      'tenantId',
      'storeId',
      'warehouseId',
      'industry',
      'industryTemplateVersionId',
      'industryTemplateSha256',
      'businessZoneId',
      'businessDayStart',
      'packageVersion',
      'previousVersion',
      'generatedAt',
      'policies',
      'lots',
    };
    if (!_sameFields(value, required) ||
        value['schemaVersion'] != '1.0' ||
        value['industry'] != 'COMMUNITY_SUPERMARKET') {
      throw StateError('LOT-DPK-107: lot package schema or industry invalid');
    }
    final version = _integer(
      value['packageVersion'],
      1,
      2147483647,
      'packageVersion',
    );
    final previous = _integer(
      value['previousVersion'],
      0,
      2147483646,
      'previousVersion',
    );
    final generatedAt = DateTime.tryParse('${value['generatedAt']}');
    final policyValues = value['policies'];
    final lotValues = value['lots'];
    if (previous >= version ||
        generatedAt == null ||
        policyValues is! List ||
        lotValues is! List ||
        policyValues.isEmpty ||
        policyValues.length + lotValues.length > 100000) {
      throw StateError('LOT-DPK-108: lot package metadata or capacity invalid');
    }
    final policies = policyValues
        .map((item) => _policy(_object(item)))
        .toList(growable: false);
    final policyBySku = {for (final policy in policies) policy.skuId: policy};
    if (policyBySku.length != policies.length) {
      throw StateError('LOT-DPK-109: duplicate lot policy SKU');
    }
    final lots = lotValues
        .map((item) => _lot(_object(item), policyBySku))
        .toList(growable: false);
    if ({for (final lot in lots) lot.lotId}.length != lots.length) {
      throw StateError('LOT-DPK-110: duplicate lot identity');
    }
    return _DecodedLotPackage(
      tenantId: _text(value['tenantId'], 20, 'tenantId'),
      storeId: _platformId(value['storeId'], 'storeId'),
      warehouseId: _ulid(value['warehouseId'], 'warehouseId'),
      industryTemplateVersionId: _platformId(
        value['industryTemplateVersionId'],
        'industryTemplateVersionId',
      ),
      industryTemplateSha256: _sha(
        value['industryTemplateSha256'],
        'industryTemplateSha256',
      ),
      businessZoneId: _text(value['businessZoneId'], 64, 'businessZoneId'),
      businessDayStart: _businessDayStart(value['businessDayStart']),
      packageVersion: version,
      previousVersion: previous,
      generatedAt: generatedAt.toUtc(),
      policies: policies,
      lots: lots,
    );
  }

  _LotPolicy _policy(Map<String, Object?> value) {
    const required = {
      'policyVersionId',
      'skuId',
      'enabled',
      'expiryBasis',
      'shelfLifeDays',
      'nearExpiryDays',
      'effectiveFrom',
      'contentSha256',
    };
    if (!_sameFields(value, required) || value['enabled'] is! bool) {
      throw StateError('LOT-DPK-111: lot policy fields invalid');
    }
    final basis = _enum(value['expiryBasis'], const {
      'PRODUCTION_DATE',
      'RECEIVED_DATE',
      'EXPLICIT_EXPIRY_DATE',
    }, 'expiryBasis');
    final shelf = value['shelfLifeDays'] == null
        ? null
        : _integer(value['shelfLifeDays'], 1, 36500, 'shelfLifeDays');
    if (basis == 'EXPLICIT_EXPIRY_DATE' && shelf != null ||
        basis != 'EXPLICIT_EXPIRY_DATE' && shelf == null) {
      throw StateError('LOT-DPK-112: expiry basis and shelf life conflict');
    }
    final effective = DateTime.tryParse('${value['effectiveFrom']}');
    if (effective == null) {
      throw StateError('LOT-DPK-113: policy effective time invalid');
    }
    return _LotPolicy(
      policyVersionId: _ulid(value['policyVersionId'], 'policyVersionId'),
      skuId: _platformId(value['skuId'], 'skuId'),
      enabled: value['enabled']! as bool,
      expiryBasis: basis,
      shelfLifeDays: shelf,
      nearExpiryDays: _integer(
        value['nearExpiryDays'],
        0,
        3650,
        'nearExpiryDays',
      ),
      effectiveFrom: effective.toUtc(),
      contentSha256: _sha(value['contentSha256'], 'contentSha256'),
    );
  }

  _LotRow _lot(Map<String, Object?> value, Map<String, _LotPolicy> policies) {
    const required = {
      'lotId',
      'skuId',
      'baseUnitId',
      'supplierLotCode',
      'internalLotCode',
      'productionDate',
      'receivedDate',
      'expiryDate',
      'policyVersionId',
      'nearExpiryDays',
      'quantity',
      'lastLedgerSequence',
      'sourceSha256',
    };
    if (!_sameFields(value, required)) {
      throw StateError('LOT-DPK-114: lot fields invalid');
    }
    final skuId = _platformId(value['skuId'], 'skuId');
    final policy = policies[skuId];
    final received = _date(value['receivedDate'], 'receivedDate');
    final expiry = _date(value['expiryDate'], 'expiryDate');
    if (policy == null || !policy.enabled || expiry.isBefore(received)) {
      throw StateError('LOT-DPK-115: lot policy or dates mismatch');
    }
    return _LotRow(
      lotId: _ulid(value['lotId'], 'lotId'),
      skuId: skuId,
      baseUnitId: _platformId(value['baseUnitId'], 'baseUnitId'),
      supplierLotCode: value['supplierLotCode'] == null
          ? null
          : _text(value['supplierLotCode'], 96, 'supplierLotCode'),
      internalLotCode: _text(value['internalLotCode'], 96, 'internalLotCode'),
      productionDate: value['productionDate'] == null
          ? null
          : _dateText(value['productionDate'], 'productionDate'),
      receivedDate: _dateText(value['receivedDate'], 'receivedDate'),
      expiryDate: _dateText(value['expiryDate'], 'expiryDate'),
      policyVersionId: _ulid(value['policyVersionId'], 'policyVersionId'),
      nearExpiryDays: _integer(
        value['nearExpiryDays'],
        0,
        3650,
        'nearExpiryDays',
      ),
      quantity: ExactLotQuantity.parse('${value['quantity']}', allowZero: true),
      lastLedgerSequence: _integer(
        value['lastLedgerSequence'],
        0,
        9007199254740991,
        'lastLedgerSequence',
      ),
      sourceSha256: _sha(value['sourceSha256'], 'sourceSha256'),
    );
  }

  Map<String, Object?> _object(Object? value) => value is Map<String, Object?>
      ? value
      : throw StateError('LOT-DPK-116: package record is not an object');
  bool _sameFields(Map<String, Object?> value, Set<String> required) {
    final actual = value.keys.toSet();
    return actual.length == required.length && actual.containsAll(required);
  }

  String _text(Object? value, int maximum, String field) =>
      value is String && value.isNotEmpty && value.length <= maximum
      ? value
      : throw StateError('LOT-DPK-117: $field invalid');
  String _platformId(Object? value, String field) =>
      RegExp(r'^[1-9][0-9]{0,18}$').hasMatch('$value')
      ? '$value'
      : throw StateError('LOT-DPK-118: $field invalid');
  String _ulid(Object? value, String field) =>
      value is String && RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(value)
      ? value
      : throw StateError('LOT-DPK-119: $field invalid');
  String _sha(Object? value, String field) =>
      value is String && RegExp(r'^[a-f0-9]{64}$').hasMatch(value)
      ? value
      : throw StateError('LOT-DPK-120: $field invalid');
  int _integer(Object? value, int minimum, int maximum, String field) =>
      value is int && value >= minimum && value <= maximum
      ? value
      : throw StateError('LOT-DPK-121: $field invalid');
  String _enum(Object? value, Set<String> allowed, String field) =>
      value is String && allowed.contains(value)
      ? value
      : throw StateError('LOT-DPK-122: $field invalid');
  DateTime _date(Object? value, String field) =>
      DateTime.tryParse('$value')?.toUtc() ??
      (throw StateError('LOT-DPK-123: $field invalid'));
  String _dateText(Object? value, String field) {
    final text = '$value';
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(text) ||
        DateTime.tryParse(text) == null) {
      throw StateError('LOT-DPK-123: $field invalid');
    }
    return text;
  }

  String _businessDayStart(Object? value) {
    final text = '$value';
    if (!RegExp(r'^(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?$')
        .hasMatch(text)) {
      throw StateError('LOT-DPK-125: businessDayStart invalid');
    }
    return text;
  }

  bool _constantTimeEquals(String left, String right) {
    if (left.length != right.length) return false;
    var difference = 0;
    for (var index = 0; index < left.length; index++) {
      difference |= left.codeUnitAt(index) ^ right.codeUnitAt(index);
    }
    return difference == 0;
  }
}

final class _DecodedLotPackage {
  const _DecodedLotPackage({
    required this.tenantId,
    required this.storeId,
    required this.warehouseId,
    required this.industryTemplateVersionId,
    required this.industryTemplateSha256,
    required this.businessZoneId,
    required this.businessDayStart,
    required this.packageVersion,
    required this.previousVersion,
    required this.generatedAt,
    required this.policies,
    required this.lots,
  });
  final String tenantId;
  final String storeId;
  final String warehouseId;
  final String industryTemplateVersionId;
  final String industryTemplateSha256;
  final String businessZoneId;
  final String businessDayStart;
  final int packageVersion;
  final int previousVersion;
  final DateTime generatedAt;
  final List<_LotPolicy> policies;
  final List<_LotRow> lots;
}

final class _LotPolicy {
  const _LotPolicy({
    required this.policyVersionId,
    required this.skuId,
    required this.enabled,
    required this.expiryBasis,
    required this.shelfLifeDays,
    required this.nearExpiryDays,
    required this.effectiveFrom,
    required this.contentSha256,
  });
  final String policyVersionId;
  final String skuId;
  final bool enabled;
  final String expiryBasis;
  final int? shelfLifeDays;
  final int nearExpiryDays;
  final DateTime effectiveFrom;
  final String contentSha256;
}

final class _LotRow {
  const _LotRow({
    required this.lotId,
    required this.skuId,
    required this.baseUnitId,
    required this.supplierLotCode,
    required this.internalLotCode,
    required this.productionDate,
    required this.receivedDate,
    required this.expiryDate,
    required this.policyVersionId,
    required this.nearExpiryDays,
    required this.quantity,
    required this.lastLedgerSequence,
    required this.sourceSha256,
  });
  final String lotId;
  final String skuId;
  final String baseUnitId;
  final String? supplierLotCode;
  final String internalLotCode;
  final String? productionDate;
  final String receivedDate;
  final String expiryDate;
  final String policyVersionId;
  final int nearExpiryDays;
  final ExactLotQuantity quantity;
  final int lastLedgerSequence;
  final String sourceSha256;
}
