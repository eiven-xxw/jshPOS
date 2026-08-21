import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../checkout/domain/checkout_models.dart';

/// 从正式下载端点取得的商品价格包；私钥和服务端对象键不会进入客户端。
final class CatalogPackageEnvelope {
  const CatalogPackageEnvelope({
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

/// 已通过签名、租户、门店、版本连续性和内容约束后原子激活的包。
final class InstalledCatalogPackage {
  const InstalledCatalogPackage({
    required this.packageVersion,
    required this.payloadSha256,
    required this.recordCount,
    required this.generatedAt,
    this.duplicate = false,
  });

  final int packageVersion;
  final String payloadSha256;
  final int recordCount;
  final DateTime generatedAt;
  final bool duplicate;
}

/// POS 销售使用的不可变商品、主单位和条码快照。
final class CatalogProductSnapshot {
  const CatalogProductSnapshot({
    required this.skuId,
    required this.skuCode,
    required this.name,
    required this.productType,
    required this.categoryId,
    required this.brandId,
    required this.unitId,
    required this.unitCode,
    required this.unitName,
    required this.decimalScale,
    required this.barcode,
  });

  final String skuId;
  final String skuCode;
  final String name;
  final String productType;
  final String categoryId;
  final String? brandId;
  final String unitId;
  final String unitCode;
  final String unitName;
  final int decimalScale;
  final String? barcode;
}

/// 从活动包解析出的成交价；金额仍以 CNY 最小货币单位保存。
final class CatalogResolvedPrice {
  const CatalogResolvedPrice({
    required this.product,
    required this.amountMinor,
    required this.priceSource,
    required this.catalogVersion,
    required this.priceVersion,
  });

  final CatalogProductSnapshot product;
  final int amountMinor;
  final String priceSource;
  final int catalogVersion;
  final int priceVersion;

  PriceQuote toCheckoutQuote() => PriceQuote.fromVerifiedPackage(
    skuId: product.skuId,
    skuCode: product.skuCode,
    productName: product.name,
    unitId: product.unitId,
    unitCode: product.unitCode,
    unitPriceMinor: amountMinor,
    priceSource: priceSource,
    barcode: product.barcode,
  );
}

/// 验证 JSHCAT canonical 包并将商品、价格和活动指针在同一 SQLite 事务内切换。
final class CatalogPackageInstaller {
  CatalogPackageInstaller(
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

  Future<InstalledCatalogPackage> install(
    CatalogPackageEnvelope envelope,
  ) async {
    final digest = sha256.convert(envelope.payload).toString();
    if (!_constantTimeEquals(digest, envelope.payloadSha256)) {
      throw StateError('CAT-DPK-101: package digest mismatch');
    }
    final key = _trustedSigningKeys[envelope.signingKeyId];
    if (key == null ||
        !await _algorithm.verify(
          envelope.payload,
          signature: Signature(envelope.signature, publicKey: key),
        )) {
      throw StateError('CAT-DPK-102: package signature is not trusted');
    }
    final decoded = _parse(envelope.payload);
    final binding = database.binding;
    if (decoded.tenantId != binding.tenantId ||
        decoded.storeId != binding.storeId) {
      throw StateError('CAT-DPK-103: tenant or store binding mismatch');
    }
    final current = database.database.select(
      'SELECT active_package_version,active_payload_sha256 FROM local_catalog_package_binding WHERE singleton_id=1 AND tenant_id=? AND store_id=?',
      [binding.tenantId, binding.storeId],
    );
    final currentVersion = current.isEmpty
        ? 0
        : current.single['active_package_version']! as int;
    if (decoded.packageVersion == currentVersion &&
        current.single['active_payload_sha256'] == digest) {
      return InstalledCatalogPackage(
        packageVersion: decoded.packageVersion,
        payloadSha256: digest,
        recordCount: decoded.products.length + decoded.prices.length,
        generatedAt: decoded.generatedAt,
        duplicate: true,
      );
    }
    if (decoded.previousVersion != currentVersion ||
        decoded.packageVersion != currentVersion + 1) {
      throw StateError('CAT-DPK-104: package version is not contiguous');
    }
    _validateReferences(decoded);
    final now = _utcNow().toUtc().toIso8601String();
    database.transaction(() {
      database.database.execute(
        '''INSERT INTO local_catalog_package_slot(package_id,tenant_id,store_id,package_version,
           previous_version,schema_version,payload_sha256,signing_key_id,generated_at,
           record_count,installed_at,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,'STAGED')''',
        [
          digest,
          binding.tenantId,
          binding.storeId,
          decoded.packageVersion,
          decoded.previousVersion,
          decoded.schemaVersion,
          digest,
          envelope.signingKeyId,
          decoded.generatedAt.toUtc().toIso8601String(),
          decoded.products.length + decoded.prices.length,
          now,
        ],
      );
      for (final product in decoded.products) {
        database.database.execute(
          '''INSERT INTO local_catalog_product(tenant_id,store_id,package_version,sku_id,sku_code,
             product_name,product_type,category_id,brand_id,unit_id,unit_code,unit_name,decimal_scale,
             ratio_numerator,ratio_denominator,barcode_value) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
          [
            binding.tenantId,
            binding.storeId,
            decoded.packageVersion,
            product.skuId,
            product.skuCode,
            product.name,
            product.productType,
            product.categoryId,
            product.brandId,
            product.unitId,
            product.unitCode,
            product.unitName,
            product.decimalScale,
            product.ratioNumerator,
            product.ratioDenominator,
            product.barcode,
          ],
        );
      }
      for (final price in decoded.prices) {
        database.database.execute(
          '''INSERT INTO local_catalog_price(tenant_id,store_id,package_version,price_book_id,book_code,
             version_no,scope_type,scope_store_id,sku_id,unit_id,amount_minor,currency,effective_from,
             effective_to) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
          [
            binding.tenantId,
            binding.storeId,
            decoded.packageVersion,
            price.priceBookId,
            price.bookCode,
            price.versionNo,
            price.scopeType,
            price.scopeStoreId,
            price.skuId,
            price.unitId,
            price.amountMinor,
            'CNY',
            price.effectiveFrom.toUtc().toIso8601String(),
            price.effectiveTo?.toUtc().toIso8601String(),
          ],
        );
      }
      database.database.execute(
        "UPDATE local_catalog_package_slot SET state='SUPERSEDED' WHERE tenant_id=? AND store_id=? AND state='ACTIVE'",
        [binding.tenantId, binding.storeId],
      );
      database.database.execute(
        '''INSERT INTO local_catalog_package_binding(singleton_id,tenant_id,store_id,active_package_version,
           active_payload_sha256,activated_at) VALUES(1,?,?,?,?,?)
           ON CONFLICT(singleton_id) DO UPDATE SET active_package_version=excluded.active_package_version,
             active_payload_sha256=excluded.active_payload_sha256,activated_at=excluded.activated_at
           WHERE excluded.tenant_id=local_catalog_package_binding.tenant_id
             AND excluded.store_id=local_catalog_package_binding.store_id''',
        [
          binding.tenantId,
          binding.storeId,
          decoded.packageVersion,
          digest,
          now,
        ],
      );
      database.database.execute(
        "UPDATE local_catalog_package_slot SET state='ACTIVE' WHERE tenant_id=? AND store_id=? AND package_version=? AND state='STAGED'",
        [binding.tenantId, binding.storeId, decoded.packageVersion],
      );
      database.checkpoint('catalog_package_after_atomic_switch');
    });
    return InstalledCatalogPackage(
      packageVersion: decoded.packageVersion,
      payloadSha256: digest,
      recordCount: decoded.products.length + decoded.prices.length,
      generatedAt: decoded.generatedAt,
    );
  }

  /// 扫码只查询活动包，条码不存在或无有效价格时失败关闭。
  CatalogResolvedPrice resolveBarcode(String barcode, {DateTime? at}) {
    if (barcode.trim().isEmpty || barcode.length > 64) {
      throw StateError('CAT-LOOKUP-001: barcode is invalid');
    }
    final rows = database.database.select(
      '''SELECT p.* FROM local_catalog_package_binding b
         JOIN local_catalog_product p ON p.tenant_id=b.tenant_id AND p.store_id=b.store_id
           AND p.package_version=b.active_package_version
         WHERE b.singleton_id=1 AND b.tenant_id=? AND b.store_id=? AND p.barcode_value=?''',
      [database.binding.tenantId, database.binding.storeId, barcode],
    );
    if (rows.length != 1) {
      throw StateError('CAT-LOOKUP-002: product is unavailable');
    }
    return _resolve(rows.single, at ?? _utcNow());
  }

  /// 按活动包中的稳定 SKU 引用恢复商品；挂单取单后仍重新校验当前包价格。
  CatalogResolvedPrice resolveSku(String skuId, {DateTime? at}) {
    if (!RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(skuId)) {
      throw StateError('CAT-LOOKUP-004: sku reference is invalid');
    }
    final rows = database.database.select(
      '''SELECT p.* FROM local_catalog_package_binding b
         JOIN local_catalog_product p ON p.tenant_id=b.tenant_id AND p.store_id=b.store_id
           AND p.package_version=b.active_package_version
         WHERE b.singleton_id=1 AND b.tenant_id=? AND b.store_id=? AND p.sku_id=?''',
      [database.binding.tenantId, database.binding.storeId, skuId],
    );
    if (rows.length != 1) {
      throw StateError('CAT-LOOKUP-002: product is unavailable');
    }
    return _resolve(rows.single, at ?? _utcNow());
  }

  /// 搜索返回活动包商品；关键词参数化并限制数量，禁止任意 SQL。
  List<CatalogResolvedPrice> search(
    String keyword, {
    DateTime? at,
    int limit = 50,
  }) {
    final normalized = keyword.trim();
    if (normalized.isEmpty ||
        normalized.length > 100 ||
        limit < 1 ||
        limit > 100) {
      throw StateError('CAT-LOOKUP-003: search input is invalid');
    }
    final escaped = normalized
        .replaceAll(r'\', r'\\')
        .replaceAll('%', r'\%')
        .replaceAll('_', r'\_');
    final rows = database.database.select(
      '''SELECT p.* FROM local_catalog_package_binding b
         JOIN local_catalog_product p ON p.tenant_id=b.tenant_id AND p.store_id=b.store_id
           AND p.package_version=b.active_package_version
         WHERE b.singleton_id=1 AND b.tenant_id=? AND b.store_id=?
           AND (p.sku_code LIKE ? ESCAPE '\\' OR p.product_name LIKE ? ESCAPE '\\')
         ORDER BY p.sku_code,p.sku_id LIMIT ?''',
      [
        database.binding.tenantId,
        database.binding.storeId,
        '%$escaped%',
        '%$escaped%',
        limit,
      ],
    );
    final when = at ?? _utcNow();
    return rows.map((row) => _resolve(row, when)).toList(growable: false);
  }

  CatalogResolvedPrice _resolve(dynamic productRow, DateTime at) {
    final rows = database.database.select(
      '''SELECT r.* FROM local_catalog_package_binding b
         JOIN local_catalog_price r ON r.tenant_id=b.tenant_id AND r.store_id=b.store_id
           AND r.package_version=b.active_package_version
         WHERE b.singleton_id=1 AND b.tenant_id=? AND b.store_id=?
           AND r.sku_id=? AND r.unit_id=? AND r.effective_from<=?
           AND (r.effective_to IS NULL OR r.effective_to>?)
         ORDER BY CASE r.scope_type WHEN 'STORE' THEN 0 ELSE 1 END,
           r.version_no DESC,r.price_book_id DESC LIMIT 2''',
      [
        database.binding.tenantId,
        database.binding.storeId,
        productRow['sku_id'],
        productRow['unit_id'],
        at.toUtc().toIso8601String(),
        at.toUtc().toIso8601String(),
      ],
    );
    if (rows.isEmpty) {
      throw StateError('CAT-PRICE-001: no active price');
    }
    final winner = rows.first;
    if (rows.length > 1 &&
        rows[1]['scope_type'] == winner['scope_type'] &&
        rows[1]['version_no'] == winner['version_no']) {
      throw StateError('CAT-PRICE-002: ambiguous active price');
    }
    return CatalogResolvedPrice(
      product: _product(productRow),
      amountMinor: winner['amount_minor']! as int,
      priceSource: winner['scope_type'] == 'STORE'
          ? 'STORE_OVERRIDE'
          : 'TENANT_BASE',
      catalogVersion: productRow['package_version']! as int,
      priceVersion: winner['version_no']! as int,
    );
  }

  CatalogProductSnapshot _product(dynamic row) => CatalogProductSnapshot(
    skuId: row['sku_id']! as String,
    skuCode: row['sku_code']! as String,
    name: row['product_name']! as String,
    productType: row['product_type']! as String,
    categoryId: row['category_id']! as String,
    brandId: row['brand_id'] as String?,
    unitId: row['unit_id']! as String,
    unitCode: row['unit_code']! as String,
    unitName: row['unit_name']! as String,
    decimalScale: row['decimal_scale']! as int,
    barcode: row['barcode_value'] as String?,
  );

  _DecodedCatalogPackage _parse(Uint8List payload) {
    final lines = const LineSplitter().convert(
      utf8.decode(payload, allowMalformed: false),
    );
    if (lines.isEmpty) throw StateError('CAT-DPK-105: empty package');
    final header = _splitEscaped(lines.first);
    if (header.length != 7 || header[0] != 'JSHCAT' || header[1] != '1.0') {
      throw StateError('CAT-DPK-106: unsupported package schema');
    }
    final version = int.tryParse(header[4]);
    final previous = int.tryParse(header[5]);
    final generated = DateTime.tryParse(header[6]);
    if (version == null ||
        previous == null ||
        version <= 0 ||
        previous < 0 ||
        previous >= version ||
        generated == null ||
        lines.length > 200001) {
      throw StateError('CAT-DPK-107: invalid package metadata or capacity');
    }
    final products = <_CatalogProduct>[];
    final prices = <_CatalogPrice>[];
    String? previousIdentity;
    for (final line in lines.skip(1)) {
      if (line.isEmpty) continue;
      final fields = _splitEscaped(line);
      if (fields.length != 3 ||
          !const {'PRODUCT', 'PRICE'}.contains(fields[0])) {
        throw StateError('CAT-DPK-108: malformed package record');
      }
      final identity = '${fields[0]}|${fields[1]}';
      if (previousIdentity != null &&
          identity.compareTo(previousIdentity) <= 0) {
        throw StateError('CAT-DPK-109: records are not canonical sorted');
      }
      final value = jsonDecode(fields[2]);
      if (value is! Map<String, Object?>) {
        throw StateError('CAT-DPK-110: record payload is not an object');
      }
      if (fields[0] == 'PRODUCT') {
        products.add(_decodeProduct(value));
      } else {
        prices.add(_decodePrice(value));
      }
      previousIdentity = identity;
    }
    if (products.isEmpty || prices.isEmpty) {
      throw StateError('CAT-DPK-111: package lacks sellable product or price');
    }
    return _DecodedCatalogPackage(
      schemaVersion: header[1],
      tenantId: header[2],
      storeId: header[3],
      packageVersion: version,
      previousVersion: previous,
      generatedAt: generated.toUtc(),
      products: products,
      prices: prices,
    );
  }

  _CatalogProduct _decodeProduct(Map<String, Object?> value) {
    const required = {
      'skuId',
      'skuCode',
      'name',
      'productType',
      'status',
      'categoryId',
      'brandId',
      'unitId',
      'unitCode',
      'unitName',
      'decimalScale',
      'ratioNumerator',
      'ratioDenominator',
      'barcode',
    };
    final fields = value.keys.toSet();
    if (fields.difference(required).isNotEmpty ||
        required.difference(fields).isNotEmpty ||
        value['status'] != 'ACTIVE') {
      throw StateError('CAT-DPK-112: product fields are invalid');
    }
    final product = _CatalogProduct(
      skuId: _platformId(value['skuId'], 'skuId'),
      skuCode: _text(value['skuCode'], 64, 'skuCode'),
      name: _text(value['name'], 200, 'name'),
      productType: _enum(value['productType'], const {
        'STANDARD',
        'WEIGHT',
        'COUNT',
      }, 'productType'),
      categoryId: _platformId(value['categoryId'], 'categoryId'),
      brandId: value['brandId'] == null
          ? null
          : _platformId(value['brandId'], 'brandId'),
      unitId: _platformId(value['unitId'], 'unitId'),
      unitCode: _text(value['unitCode'], 64, 'unitCode'),
      unitName: _text(value['unitName'], 100, 'unitName'),
      decimalScale: _integer(value['decimalScale'], 0, 6, 'decimalScale'),
      ratioNumerator: _integer(
        value['ratioNumerator'],
        1,
        9007199254740991,
        'ratioNumerator',
      ),
      ratioDenominator: _integer(
        value['ratioDenominator'],
        1,
        9007199254740991,
        'ratioDenominator',
      ),
      barcode: value['barcode'] == null
          ? null
          : _text(value['barcode'], 64, 'barcode'),
    );
    return product;
  }

  _CatalogPrice _decodePrice(Map<String, Object?> value) {
    const required = {
      'priceBookId',
      'bookCode',
      'versionNo',
      'scopeType',
      'storeId',
      'skuId',
      'unitId',
      'amountMinor',
      'currency',
      'effectiveFrom',
      'effectiveTo',
    };
    final fields = value.keys.toSet();
    if (fields.difference(required).isNotEmpty ||
        required.difference(fields).isNotEmpty ||
        value['currency'] != 'CNY') {
      throw StateError('CAT-DPK-113: price fields are invalid');
    }
    final scope = _enum(value['scopeType'], const {
      'TENANT_BASE',
      'STORE',
    }, 'scopeType');
    final storeId = value['storeId'] == null
        ? null
        : _platformId(value['storeId'], 'storeId');
    if (scope == 'TENANT_BASE' && storeId != null ||
        scope == 'STORE' && storeId != database.binding.storeId) {
      throw StateError('CAT-DPK-114: price scope does not match package store');
    }
    final from = DateTime.tryParse('${value['effectiveFrom']}');
    final to = value['effectiveTo'] == null
        ? null
        : DateTime.tryParse('${value['effectiveTo']}');
    if (from == null || to != null && !to.isAfter(from)) {
      throw StateError('CAT-DPK-115: price window is invalid');
    }
    return _CatalogPrice(
      priceBookId: _platformId(value['priceBookId'], 'priceBookId'),
      bookCode: _text(value['bookCode'], 64, 'bookCode'),
      versionNo: _integer(value['versionNo'], 1, 2147483647, 'versionNo'),
      scopeType: scope,
      scopeStoreId: storeId,
      skuId: _platformId(value['skuId'], 'skuId'),
      unitId: _platformId(value['unitId'], 'unitId'),
      amountMinor: _integer(
        value['amountMinor'],
        0,
        9007199254740991,
        'amountMinor',
      ),
      effectiveFrom: from.toUtc(),
      effectiveTo: to?.toUtc(),
    );
  }

  void _validateReferences(_DecodedCatalogPackage value) {
    final products = <String>{};
    final barcodes = <String>{};
    for (final product in value.products) {
      final key = '${product.skuId}|${product.unitId}';
      if (!products.add(key) ||
          product.barcode != null && !barcodes.add(product.barcode!)) {
        throw StateError('CAT-DPK-116: duplicate product or barcode');
      }
    }
    if (value.prices.any(
      (price) => !products.contains('${price.skuId}|${price.unitId}'),
    )) {
      throw StateError('CAT-DPK-117: price references missing product');
    }
  }

  List<String> _splitEscaped(String value) {
    final fields = <String>[];
    final buffer = StringBuffer();
    var escaped = false;
    for (var index = 0; index < value.length; index++) {
      final char = value[index];
      if (escaped) {
        switch (char) {
          case r'\':
            buffer.write(r'\');
            break;
          case 'p':
            buffer.write('|');
            break;
          case 'r':
            buffer.write('\r');
            break;
          case 'n':
            buffer.write('\n');
            break;
          default:
            throw const FormatException('unknown package escape');
        }
        escaped = false;
      } else if (char == r'\') {
        escaped = true;
      } else if (char == '|') {
        fields.add(buffer.toString());
        buffer.clear();
      } else {
        buffer.write(char);
      }
    }
    if (escaped) throw const FormatException('dangling package escape');
    fields.add(buffer.toString());
    return fields;
  }

  String _platformId(Object? value, String field) {
    final text = '$value';
    if (!RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(text)) {
      throw StateError('CAT-DPK-118: $field is not a platform ID');
    }
    return text;
  }

  String _text(Object? value, int maximum, String field) {
    if (value is! String || value.isEmpty || value.length > maximum) {
      throw StateError('CAT-DPK-119: $field is invalid');
    }
    return value;
  }

  int _integer(Object? value, int minimum, int maximum, String field) {
    if (value is! int || value < minimum || value > maximum) {
      throw StateError('CAT-DPK-120: $field is invalid');
    }
    return value;
  }

  String _enum(Object? value, Set<String> allowed, String field) {
    if (value is! String || !allowed.contains(value)) {
      throw StateError('CAT-DPK-121: $field is invalid');
    }
    return value;
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

final class _DecodedCatalogPackage {
  const _DecodedCatalogPackage({
    required this.schemaVersion,
    required this.tenantId,
    required this.storeId,
    required this.packageVersion,
    required this.previousVersion,
    required this.generatedAt,
    required this.products,
    required this.prices,
  });
  final String schemaVersion;
  final String tenantId;
  final String storeId;
  final int packageVersion;
  final int previousVersion;
  final DateTime generatedAt;
  final List<_CatalogProduct> products;
  final List<_CatalogPrice> prices;
}

final class _CatalogProduct {
  const _CatalogProduct({
    required this.skuId,
    required this.skuCode,
    required this.name,
    required this.productType,
    required this.categoryId,
    required this.brandId,
    required this.unitId,
    required this.unitCode,
    required this.unitName,
    required this.decimalScale,
    required this.ratioNumerator,
    required this.ratioDenominator,
    required this.barcode,
  });
  final String skuId;
  final String skuCode;
  final String name;
  final String productType;
  final String categoryId;
  final String? brandId;
  final String unitId;
  final String unitCode;
  final String unitName;
  final int decimalScale;
  final int ratioNumerator;
  final int ratioDenominator;
  final String? barcode;
}

final class _CatalogPrice {
  const _CatalogPrice({
    required this.priceBookId,
    required this.bookCode,
    required this.versionNo,
    required this.scopeType,
    required this.scopeStoreId,
    required this.skuId,
    required this.unitId,
    required this.amountMinor,
    required this.effectiveFrom,
    required this.effectiveTo,
  });
  final String priceBookId;
  final String bookCode;
  final int versionNo;
  final String scopeType;
  final String? scopeStoreId;
  final String skuId;
  final String unitId;
  final int amountMinor;
  final DateTime effectiveFrom;
  final DateTime? effectiveTo;
}
