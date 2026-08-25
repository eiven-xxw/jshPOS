import 'dart:convert';

import 'package:cryptography/cryptography.dart';
import 'package:flutter/widgets.dart';
import 'package:path_provider/path_provider.dart';

import '../features/checkout/domain/ulid_generator.dart';
import '../features/session/domain/pos_session_models.dart';
import '../features/session/infrastructure/http_pos_session_repository.dart';
import '../features/session/infrastructure/ruoyi_api_request_encryptor.dart';
import '../infrastructure/runtime/session_bound_pos_runtime.dart';
import 'jshpos_app.dart';

/// 未接入 Android Keystore/厂商安全区时明确失败关闭，禁止从 dart-define 读取设备秘密。
final class LockedPosTerminalMaterialProvider
    implements PosTerminalMaterialProvider {
  const LockedPosTerminalMaterialProvider();

  @override
  Future<PosTerminalMaterial> load() async {
    throw const PosSessionFailure(
      'HWD_SECURE_CREDENTIAL_UNAVAILABLE',
      '终端安全凭据尚未由认证设备提供，请联系管理员。',
    );
  }
}

/// 可公开的 POS 运行配置；设备凭据、员工口令和访问令牌不属于本配置。
final class PosRuntimeSettings {
  const PosRuntimeSettings({
    required this.serverUri,
    required this.clientId,
    required this.loginRsaPublicKey,
    required this.catalogPackageVersion,
    required this.promotionPackageVersion,
    required this.catalogSigningKeys,
    required this.promotionSigningKeys,
    required this.industryTemplateVersion,
    required this.returnWarehouseId,
    required this.configVersion,
    required this.cashDifferenceApprovalMinor,
  });

  final Uri serverUri;
  final String clientId;
  final String loginRsaPublicKey;
  final int catalogPackageVersion;
  final int promotionPackageVersion;
  final Map<String, SimplePublicKey> catalogSigningKeys;
  final Map<String, SimplePublicKey> promotionSigningKeys;
  final String industryTemplateVersion;
  final String returnWarehouseId;
  final int configVersion;
  final int cashDifferenceApprovalMinor;

  static PosRuntimeSettings? fromEnvironment() => parse(const {
    'serverUrl': String.fromEnvironment('JSH_POS_SERVER_URL'),
    'clientId': String.fromEnvironment('JSH_POS_CLIENT_ID'),
    'loginKey': String.fromEnvironment('JSH_POS_LOGIN_RSA_PUBLIC_KEY'),
    'catalogVersion': String.fromEnvironment('JSH_POS_CATALOG_PACKAGE_VERSION'),
    'promotionVersion': String.fromEnvironment(
      'JSH_POS_PROMOTION_PACKAGE_VERSION',
    ),
    'catalogKeys': String.fromEnvironment('JSH_POS_CATALOG_SIGNING_KEYS'),
    'promotionKeys': String.fromEnvironment('JSH_POS_PROMOTION_SIGNING_KEYS'),
    'industryTemplate': String.fromEnvironment('JSH_POS_INDUSTRY_TEMPLATE'),
    'returnWarehouseId': String.fromEnvironment('JSH_POS_RETURN_WAREHOUSE_ID'),
    'configVersion': String.fromEnvironment('JSH_POS_CONFIG_VERSION'),
    'cashDifferenceMinor': String.fromEnvironment(
      'JSH_POS_CASH_DIFFERENCE_MINOR',
    ),
  });

  /// 空配置返回 null；半配置或不安全配置直接抛错，避免静默降级到错误环境。
  static PosRuntimeSettings? parse(Map<String, String> source) {
    if (source.values.every((value) => value.trim().isEmpty)) return null;
    String required(String key) {
      final value = source[key]?.trim() ?? '';
      if (value.isEmpty) throw FormatException('RUNTIME_CONFIG_MISSING:$key');
      return value;
    }

    final server = Uri.parse(required('serverUrl'));
    final isLoopback = const {
      'localhost',
      '127.0.0.1',
      '::1',
    }.contains(server.host);
    if (!server.hasScheme ||
        server.host.isEmpty ||
        (server.scheme != 'https' && !isLoopback)) {
      throw const FormatException('RUNTIME_CONFIG_TLS_REQUIRED');
    }
    int positive(String key) {
      final value = int.tryParse(required(key));
      if (value == null || value <= 0) {
        throw FormatException('RUNTIME_CONFIG_INVALID:$key');
      }
      return value;
    }

    final threshold = int.tryParse(required('cashDifferenceMinor'));
    if (threshold == null || threshold < 0) {
      throw const FormatException('RUNTIME_CONFIG_INVALID:cashDifferenceMinor');
    }
    final returnWarehouseId = required('returnWarehouseId');
    if (!UlidGenerator.isCanonical(returnWarehouseId)) {
      throw const FormatException('RUNTIME_CONFIG_INVALID:returnWarehouseId');
    }
    return PosRuntimeSettings(
      serverUri: server.path.endsWith('/')
          ? server
          : server.replace(path: '${server.path}/'),
      clientId: required('clientId'),
      loginRsaPublicKey: required('loginKey'),
      catalogPackageVersion: positive('catalogVersion'),
      promotionPackageVersion: positive('promotionVersion'),
      catalogSigningKeys: _signingKeys(required('catalogKeys')),
      promotionSigningKeys: _signingKeys(required('promotionKeys')),
      industryTemplateVersion: required('industryTemplate'),
      returnWarehouseId: returnWarehouseId,
      configVersion: positive('configVersion'),
      cashDifferenceApprovalMinor: threshold,
    );
  }
}

/// 应用唯一组合根。配置缺失时保留 Locked 默认；配置完整时装配正式会话与业务运行时。
final class PosApplicationBootstrap {
  const PosApplicationBootstrap._();

  static Future<Widget> create({
    PosRuntimeSettings? settings,
    PosTerminalMaterialProvider materialProvider =
        const LockedPosTerminalMaterialProvider(),
  }) async {
    final resolved = settings ?? PosRuntimeSettings.fromEnvironment();
    if (resolved == null) return const JshposApp();
    final session = HttpPosSessionRepository(
      baseUri: resolved.serverUri,
      clientId: resolved.clientId,
      materialProvider: materialProvider,
      loginEncryptor: RuoYiApiRequestEncryptor(resolved.loginRsaPublicKey),
    );
    final support = await getApplicationSupportDirectory();
    late final SessionBoundPosRuntime runtime;
    final assembler = FilePosBusinessRuntimeAssembler(
      databasePathProvider: (binding) async =>
          '${support.path}/pos-${binding.tenantId}-${binding.storeId}-${binding.deviceId}.sqlite3',
      baseUri: resolved.serverUri,
      clientId: resolved.clientId,
      accessTokenProvider: session.accessToken,
      catalogPackageVersion: resolved.catalogPackageVersion,
      promotionPackageVersion: resolved.promotionPackageVersion,
      catalogSigningKeys: resolved.catalogSigningKeys,
      promotionSigningKeys: resolved.promotionSigningKeys,
      industryTemplateVersion: resolved.industryTemplateVersion,
      returnWarehouseId: resolved.returnWarehouseId,
      configVersion: resolved.configVersion,
      cashDifferenceApprovalMinor: resolved.cashDifferenceApprovalMinor,
    );
    runtime = SessionBoundPosRuntime(sessions: session, assembler: assembler);
    return JshposApp(
      industryTemplateVersion: resolved.industryTemplateVersion,
      sessionRepository: runtime,
      saleService: runtime,
      returnService: runtime,
      shiftService: runtime,
    );
  }
}

Map<String, SimplePublicKey> _signingKeys(String source) {
  final result = <String, SimplePublicKey>{};
  for (final item in source.split(',')) {
    final separator = item.indexOf(':');
    if (separator < 1 || separator == item.length - 1) {
      throw const FormatException('RUNTIME_SIGNING_KEY_INVALID');
    }
    final keyId = item.substring(0, separator).trim();
    final bytes = base64Decode(item.substring(separator + 1).trim());
    if (!RegExp(r'^[A-Za-z0-9._-]{1,64}$').hasMatch(keyId) ||
        bytes.length != 32) {
      throw const FormatException('RUNTIME_SIGNING_KEY_INVALID');
    }
    result[keyId] = SimplePublicKey(bytes, type: KeyPairType.ed25519);
  }
  if (result.isEmpty) {
    throw const FormatException('RUNTIME_SIGNING_KEY_MISSING');
  }
  return Map.unmodifiable(result);
}
