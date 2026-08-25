import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

import '../application/pos_session_repository.dart';
import '../domain/pos_session_models.dart';
import 'ruoyi_api_request_encryptor.dart';

/// 终端安全存储提供的最小认证材料。实现方不得把 deviceCredential 写入日志或普通配置文件。
final class PosTerminalMaterial {
  const PosTerminalMaterial({
    required this.deviceId,
    required this.deviceCredential,
    required this.deviceFingerprintSha256,
    required this.publicKeySha256,
    required this.appVersion,
    required this.protocolVersion,
    required this.schemaVersion,
  });

  final String deviceId;
  final String deviceCredential;
  final String deviceFingerprintSha256;
  final String publicKeySha256;
  final String appVersion;
  final String protocolVersion;
  final String schemaVersion;
}

abstract interface class PosTerminalMaterialProvider {
  Future<PosTerminalMaterial> load();
}

/// 正式自有服务端会话适配器；只访问终端注册、RuoYi 登录和用户权限 API。
final class HttpPosSessionRepository implements PosSessionRepository {
  HttpPosSessionRepository({
    required Uri baseUri,
    required this.clientId,
    required this.materialProvider,
    required this.loginEncryptor,
    HttpClient? client,
    this.timeout = const Duration(seconds: 12),
  }) : baseUri = baseUri.path.endsWith('/')
           ? baseUri
           : baseUri.replace(path: '${baseUri.path}/'),
       _client = client ?? HttpClient();

  final Uri baseUri;
  final String clientId;
  final PosTerminalMaterialProvider materialProvider;
  final ApiRequestEncryptor loginEncryptor;
  final HttpClient _client;
  final Duration timeout;

  PosTerminalMaterial? _material;
  TrustedTerminalContext? _terminal;
  String? _boundUserId;
  String? _accessToken;
  EmployeeSession? _employee;

  /// 仅向其他自有服务端适配器提供内存令牌，不持久化、不返回给页面。
  Future<String> accessToken() async {
    final token = _accessToken;
    if (token == null || token.isEmpty) {
      throw const PosSessionFailure('SESSION_REQUIRED', '员工会话不存在或已经失效。');
    }
    return token;
  }

  @override
  Future<TrustedTerminalContext> verifyTerminal(DeviceSnapshot device) =>
      _verifyTerminal(device, clearEmployeeSession: true);

  Future<TrustedTerminalContext> _verifyTerminal(
    DeviceSnapshot device, {
    required bool clearEmployeeSession,
  }) async {
    final material = await materialProvider.load();
    final data = await _request(
      'POST',
      'api/pos/v1/terminals/authenticate',
      body: <String, Object?>{
        'deviceId': material.deviceId,
        'deviceCredential': material.deviceCredential,
        'deviceFingerprintSha256': material.deviceFingerprintSha256,
        'publicKeySha256': material.publicKeySha256,
        'appVersion': material.appVersion,
        'protocolVersion': material.protocolVersion,
        'schemaVersion': material.schemaVersion,
        'clientTime': DateTime.now().toUtc().toIso8601String(),
      },
    );
    if (_text(data, 'deviceId') != material.deviceId) {
      throw const PosSessionFailure(
        'TERMINAL_CONTEXT_MISMATCH',
        '服务端终端身份与本机凭据不一致。',
      );
    }
    final approved = _strings(data['approvedCapabilities'])
        .map(DeviceCapability.new)
        .toSet();
    final terminal = TrustedTerminalContext(
      tenantId: _text(data, 'tenantId'),
      tenantName: _text(data, 'tenantName', fallback: _text(data, 'tenantId')),
      orgUnitId: _text(data, 'orgUnitId'),
      storeId: _text(data, 'storeId'),
      storeName: _text(data, 'storeName'),
      deviceId: _text(data, 'deviceId'),
      terminalId: _text(data, 'terminalId'),
      terminalName: _text(data, 'terminalName'),
      storeTimezone: _text(data, 'storeTimezone'),
      businessDate: _text(data, 'businessDate'),
      status: _text(data, 'status'),
      protocolVersion: _text(data, 'protocolVersion'),
      validUntil: DateTime.parse(_text(data, 'validUntil')).toUtc(),
      approvedCapabilities: approved,
    );
    _material = material;
    _boundUserId = _nullableText(data['boundUserId']);
    _terminal = terminal;
    if (clearEmployeeSession) {
      _accessToken = null;
      _employee = null;
    }
    return terminal;
  }

  @override
  Future<PosLoginResult> authenticate(
    TrustedTerminalContext terminal,
    EmployeeLoginCommand command,
  ) async {
    _requireTerminal(terminal);
    final encrypted = loginEncryptor.encryptJson(<String, Object?>{
      'tenantId': terminal.tenantId,
      'clientId': clientId,
      'grantType': 'password',
      'username': command.loginName,
      'password': command.secret,
    });
    final login = await _request(
      'POST',
      'auth/login',
      rawBody: encrypted.body,
      extraHeaders: <String, String>{
        'encrypt-key': encrypted.encryptKey,
        'clientid': clientId,
        'X-Correlation-Id': command.correlationId,
      },
    );
    final token = _text(login, 'access_token');
    final expiresIn = _positiveInt(login['expire_in'], 'AUTH_RESPONSE_INVALID');
    _accessToken = token;
    try {
      final info = await _request('GET', 'system/user/getInfo', bearer: token);
      final user = _map(info['user']);
      final userId = _text(user, 'userId');
      if (_text(user, 'tenantId') != terminal.tenantId ||
          (_boundUserId != null && _boundUserId != userId)) {
        throw const PosSessionFailure(
          'SESSION_CONTEXT_MISMATCH',
          '员工、租户或终端绑定不一致。',
        );
      }
      final now = DateTime.now().toUtc();
      final permissions = _permissions(info['permissions']);
      final employee = EmployeeSession(
        employeeId: userId,
        employeeName: _text(
          user,
          'nickName',
          fallback: _text(user, 'userName'),
        ),
        sessionRef: 'sha256:${sha256.convert(utf8.encode(token))}',
        authenticatedAt: now,
        expiresAt: now.add(Duration(seconds: expiresIn)),
        roles: _strings(info['roles']).toSet(),
        permissions: permissions,
      );
      _employee = employee;
      return PosLoginResult(employee: employee);
    } catch (_) {
      _accessToken = null;
      _employee = null;
      rethrow;
    }
  }

  @override
  Future<PosSessionRefresh> refresh(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
  ) async {
    _requireTerminal(terminal);
    if (_employee?.employeeId != employee.employeeId) {
      throw const PosSessionFailure('SESSION_CONTEXT_MISMATCH', '员工会话引用不一致。');
    }
    final token = await accessToken();
    final refreshedTerminal = await _verifyTerminal(
      const DeviceSnapshot(
        metadata: DeviceMetadata(
          manufacturer: 'verified-session-refresh',
          model: 'verified-session-refresh',
          androidRelease: '0',
          androidSdk: 0,
          adapterVersion: '1.0',
        ),
        capabilities: <DeviceCapability>{},
      ),
      clearEmployeeSession: false,
    );
    final info = await _request('GET', 'system/user/getInfo', bearer: token);
    final user = _map(info['user']);
    if (_text(user, 'tenantId') != refreshedTerminal.tenantId ||
        _text(user, 'userId') != employee.employeeId ||
        (_boundUserId != null && _boundUserId != employee.employeeId)) {
      throw const PosSessionFailure(
        'SESSION_CONTEXT_MISMATCH',
        '刷新后的员工、租户或终端绑定不一致。',
      );
    }
    final permissions = _permissions(info['permissions']);
    final refreshedEmployee = EmployeeSession(
      employeeId: employee.employeeId,
      employeeName: _text(user, 'nickName', fallback: _text(user, 'userName')),
      sessionRef: employee.sessionRef,
      authenticatedAt: employee.authenticatedAt,
      expiresAt: employee.expiresAt,
      roles: _strings(info['roles']).toSet(),
      permissions: permissions,
    );
    _employee = refreshedEmployee;
    return PosSessionRefresh(
      terminal: refreshedTerminal,
      employee: refreshedEmployee,
    );
  }

  @override
  Future<void> logout(
    TrustedTerminalContext terminal,
    EmployeeSession employee,
    String correlationId,
  ) async {
    _requireTerminal(terminal);
    final token = _accessToken;
    if (token != null) {
      await _request(
        'POST',
        'auth/logout',
        bearer: token,
        extraHeaders: <String, String>{'X-Correlation-Id': correlationId},
      );
    }
    _accessToken = null;
    _employee = null;
  }

  Future<Map<String, Object?>> _request(
    String method,
    String path, {
    Map<String, Object?>? body,
    String? rawBody,
    String? bearer,
    Map<String, String> extraHeaders = const {},
  }) async {
    try {
      final request = await _client
          .openUrl(method, baseUri.resolve(path))
          .timeout(timeout);
      request.headers
        ..set(HttpHeaders.acceptHeader, ContentType.json.mimeType)
        ..set('clientid', clientId);
      if (bearer != null) {
        request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $bearer');
      }
      extraHeaders.forEach(request.headers.set);
      if (body != null || rawBody != null) {
        request.headers.contentType = ContentType.json;
        request.write(rawBody ?? jsonEncode(body));
      }
      final response = await request.close().timeout(timeout);
      final text = await utf8.decoder.bind(response).join().timeout(timeout);
      final envelope = jsonDecode(text);
      if (envelope is! Map) throw const FormatException();
      final value = envelope.cast<String, Object?>();
      final code = value['code'];
      if (response.statusCode < 200 ||
          response.statusCode >= 300 ||
          (code is num && code.toInt() != 200)) {
        throw PosSessionFailure(
          'SESSION_HTTP_${response.statusCode}',
          _safeMessage(value['msg']),
        );
      }
      final data = value['data'];
      if (data == null && path == 'auth/logout') return const {};
      return _map(data);
    } on PosSessionFailure {
      rethrow;
    } on TimeoutException {
      throw const PosSessionFailure('SESSION_NETWORK_TIMEOUT', '会话请求超时，请检查网络。');
    } on SocketException {
      throw const PosSessionFailure(
        'SESSION_NETWORK_UNAVAILABLE',
        '无法连接自有服务端。',
      );
    } on FormatException {
      throw const PosSessionFailure('SESSION_RESPONSE_INVALID', '服务端会话响应格式无效。');
    }
  }

  void _requireTerminal(TrustedTerminalContext terminal) {
    final verified = _terminal;
    if (verified == null ||
        verified.tenantId != terminal.tenantId ||
        verified.storeId != terminal.storeId ||
        verified.deviceId != terminal.deviceId ||
        verified.terminalId != terminal.terminalId ||
        _material == null) {
      throw const PosSessionFailure('TERMINAL_CONTEXT_MISMATCH', '终端可信上下文不一致。');
    }
  }

  void close() => _client.close(force: true);
}

Map<String, Object?> _map(Object? value) {
  if (value is Map) return value.cast<String, Object?>();
  throw const FormatException();
}

String _text(Map<String, Object?> source, String key, {String? fallback}) {
  final value = source[key];
  if (value is String && value.trim().isNotEmpty) return value.trim();
  if (value is num) return value.toString();
  if (fallback != null && fallback.isNotEmpty) return fallback;
  throw const FormatException();
}

String? _nullableText(Object? value) {
  if (value == null) return null;
  if (value is String && value.isNotEmpty) return value;
  if (value is num) return value.toString();
  throw const FormatException();
}

int _positiveInt(Object? value, String code) {
  final parsed = value is int ? value : int.tryParse(value.toString());
  if (parsed == null || parsed <= 0) throw PosSessionFailure(code, '会话有效期无效。');
  return parsed;
}

List<String> _strings(Object? value) => value is List
    ? value
          .whereType<String>()
          .where((item) => item.isNotEmpty)
          .toList(growable: false)
    : const [];

PosPermission? _permission(String wire) {
  for (final permission in PosPermission.values) {
    if (permission.wireCode == wire) return permission;
  }
  return switch (wire) {
    'pos:basket:operate' => PosPermission.saleOperate,
    'pos:cash:collect' => PosPermission.cashSettle,
    'promotion:manual:authorize' => PosPermission.manualDiscount,
    'promotion:manual:approve' => PosPermission.approveDiscount,
    'pos:sync:operate' => PosPermission.syncView,
    _ => null,
  };
}

Set<PosPermission> _permissions(Object? value) {
  final wires = _strings(value).toSet();
  // RuoYi 超级管理员的通配授权与服务端语义保持一致；普通角色只映射明确的正式权限。
  if (wires.contains('*:*:*')) return PosPermission.values.toSet();
  final result = wires.map(_permission).whereType<PosPermission>().toSet();
  // 登录能力表示终端、员工、租户绑定已经由服务端验证，不额外扩大任何业务权限。
  result.add(PosPermission.sessionLogin);
  return result;
}

String _safeMessage(Object? value) {
  final text = value is String ? value.trim() : '';
  if (text.isEmpty || text.length > 180) return '会话请求被服务端拒绝。';
  return text.replaceAll(RegExp(r'[\r\n\t]+'), ' ');
}
