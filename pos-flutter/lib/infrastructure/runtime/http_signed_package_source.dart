import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import '../../features/catalog/infrastructure/catalog_package_installer.dart';
import '../../features/promotion/infrastructure/promotion_package_installer.dart';
import '../../features/promotion/infrastructure/member_benefit_package_installer.dart';
import '../../features/session/domain/pos_session_models.dart';

/// 只从自有服务端下载带摘要和签名的正式离线包，不接受客户端租户覆盖值。
final class HttpSignedPackageSource {
  HttpSignedPackageSource({
    required Uri baseUri,
    required this.accessTokenProvider,
    HttpClient? client,
    this.timeout = const Duration(seconds: 30),
  }) : baseUri = baseUri.path.endsWith('/')
           ? baseUri
           : baseUri.replace(path: '${baseUri.path}/'),
       _client = client ?? HttpClient();

  final Uri baseUri;
  final Future<String> Function() accessTokenProvider;
  final HttpClient _client;
  final Duration timeout;

  Future<CatalogPackageEnvelope> catalog({
    required String storeId,
    required int packageVersion,
  }) async {
    final artifact = await _download(
      'api/v1/catalog/packages/$packageVersion/content',
      storeId,
    );
    return CatalogPackageEnvelope(
      payload: artifact.payload,
      payloadSha256: artifact.sha256,
      signature: artifact.signature,
      signingKeyId: artifact.keyId,
    );
  }

  Future<PromotionPackageEnvelope> promotion({
    required String storeId,
    required int packageVersion,
  }) async {
    final artifact = await _download(
      'api/v1/promotions/packages/$packageVersion/content',
      storeId,
    );
    return PromotionPackageEnvelope(
      payload: artifact.payload,
      payloadSha256: artifact.sha256,
      signature: artifact.signature,
      signingKeyId: artifact.keyId,
    );
  }

  Future<MemberBenefitPackageEnvelope> memberBenefit({
    required String storeId,
    required int packageVersion,
  }) async {
    final artifact = await _download(
      'api/v1/promotions/member-benefit-packages/$packageVersion/content',
      storeId,
    );
    return MemberBenefitPackageEnvelope(
      payload: artifact.payload,
      payloadSha256: artifact.sha256,
      signature: artifact.signature,
      signingKeyId: artifact.keyId,
    );
  }

  Future<_SignedArtifact> _download(String path, String storeId) async {
    if (!RegExp(r'^[1-9][0-9]{0,18}$').hasMatch(storeId)) {
      throw const PosSessionFailure('PACKAGE_STORE_INVALID', '离线包门店上下文无效。');
    }
    try {
      final target = baseUri
          .resolve(path)
          .replace(queryParameters: <String, String>{'storeId': storeId});
      final request = await _client.getUrl(target).timeout(timeout);
      request.headers
        ..set(
          HttpHeaders.authorizationHeader,
          'Bearer ${await accessTokenProvider()}',
        )
        ..set(HttpHeaders.acceptHeader, ContentType.binary.mimeType);
      final response = await request.close().timeout(timeout);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        await response.drain<void>();
        throw PosSessionFailure(
          'PACKAGE_HTTP_${response.statusCode}',
          '离线包下载被服务端拒绝。',
        );
      }
      final payload = await response
          .fold<List<int>>(<int>[], (all, part) {
            if (all.length + part.length > 64 * 1024 * 1024) {
              throw const PosSessionFailure(
                'PACKAGE_TOO_LARGE',
                '离线包超过客户端安全上限。',
              );
            }
            all.addAll(part);
            return all;
          })
          .timeout(timeout);
      final sha = response.headers.value('X-JSH-Payload-Sha256');
      final keyId = response.headers.value('X-JSH-Signing-Key-Id');
      final signature = response.headers.value('X-JSH-Signature');
      if (sha == null ||
          !RegExp(r'^[a-f0-9]{64}$').hasMatch(sha) ||
          keyId == null ||
          keyId.isEmpty ||
          signature == null) {
        throw const PosSessionFailure('PACKAGE_HEADERS_INVALID', '离线包签名元数据缺失。');
      }
      return _SignedArtifact(
        payload: Uint8List.fromList(payload),
        sha256: sha,
        keyId: keyId,
        signature: Uint8List.fromList(base64Decode(signature)),
      );
    } on PosSessionFailure {
      rethrow;
    } on TimeoutException {
      throw const PosSessionFailure('PACKAGE_DOWNLOAD_TIMEOUT', '离线包下载超时。');
    } on SocketException {
      throw const PosSessionFailure(
        'PACKAGE_NETWORK_UNAVAILABLE',
        '离线包下载网络不可用。',
      );
    } on FormatException {
      throw const PosSessionFailure('PACKAGE_SIGNATURE_INVALID', '离线包签名编码无效。');
    }
  }

  void close() => _client.close(force: true);
}

final class _SignedArtifact {
  const _SignedArtifact({
    required this.payload,
    required this.sha256,
    required this.keyId,
    required this.signature,
  });

  final Uint8List payload;
  final String sha256;
  final String keyId;
  final Uint8List signature;
}
