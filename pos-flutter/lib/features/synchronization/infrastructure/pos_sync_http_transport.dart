import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../domain/sync_models.dart';

typedef AccessTokenProvider = Future<String> Function();

final class PosSyncHttpTransport implements PosSyncTransport {
  PosSyncHttpTransport({
    required Uri baseUri,
    required this.deviceId,
    required this.accessTokenProvider,
    HttpClient? client,
    this.timeout = const Duration(seconds: 15),
  }) : baseUri = baseUri.path.endsWith('/')
           ? baseUri
           : baseUri.replace(path: '${baseUri.path}/'),
       _client = client ?? HttpClient();

  final Uri baseUri;
  final String deviceId;
  final AccessTokenProvider accessTokenProvider;
  final Duration timeout;
  final HttpClient _client;

  @override
  Future<SyncBootstrap> bootstrap(String correlationId) async {
    final data = await _requestObject(
      'POST',
      'sync/bootstrap',
      correlationId: correlationId,
    );
    return SyncBootstrap.fromJson(data);
  }

  @override
  Future<SyncPushResponse> push(SyncPushBatch batch) async {
    final data = await _requestObject(
      'POST',
      'sync/push',
      correlationId: batch.events.first.correlationId,
      body: batch.toJson(),
    );
    return SyncPushResponse.fromJson(data);
  }

  @override
  Future<SyncEventAck> result(String eventId, String correlationId) async {
    final data = await _requestObject(
      'GET',
      'sync/results/$eventId',
      correlationId: correlationId,
    );
    return SyncEventAck.fromJson(data);
  }

  @override
  Future<SyncPullPage> pull({
    required String stream,
    required String correlationId,
    String? cursor,
    int limit = 100,
  }) async {
    final query = <String, String>{'stream': stream, 'limit': '$limit'};
    if (cursor != null) query['cursor'] = cursor;
    final data = await _requestObject(
      'GET',
      'sync/pull',
      correlationId: correlationId,
      query: query,
    );
    return SyncPullPage.fromJson(data);
  }

  @override
  Future<void> acknowledge(
    SyncAckCommand command,
    String correlationId,
  ) async {
    await _request(
      'POST',
      'sync/ack',
      correlationId: correlationId,
      body: command.toJson(),
      expectBody: false,
    );
  }

  Future<Map<String, Object?>> _requestObject(
    String method,
    String path, {
    required String correlationId,
    Map<String, String>? query,
    Object? body,
  }) async {
    final decoded = await _request(
      method,
      path,
      correlationId: correlationId,
      query: query,
      body: body,
      expectBody: true,
    );
    if (decoded is! Map<String, Object?>) {
      throw const SyncTransportException(
        'SYNC_RESPONSE_INVALID',
        'response data is not an object',
        retryable: false,
      );
    }
    return decoded;
  }

  Future<Object?> _request(
    String method,
    String path, {
    required String correlationId,
    Map<String, String>? query,
    Object? body,
    required bool expectBody,
  }) async {
    final target = baseUri.resolve(path).replace(queryParameters: query);
    try {
      final request = await _client.openUrl(method, target).timeout(timeout);
      request.headers
        ..set(HttpHeaders.authorizationHeader, 'Bearer ${await accessTokenProvider()}')
        ..set('X-Device-Id', deviceId)
        ..set('X-Correlation-Id', correlationId)
        ..set(HttpHeaders.acceptHeader, ContentType.json.mimeType);
      if (body != null) {
        request.headers.contentType = ContentType.json;
        request.write(jsonEncode(body));
      }
      final response = await request.close().timeout(timeout);
      final text = await utf8.decoder.bind(response).join().timeout(timeout);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw SyncTransportException(
          'SYNC_HTTP_${response.statusCode}',
          text.isEmpty ? 'remote synchronization request failed' : text,
          retryable: response.statusCode == 408 ||
              response.statusCode == 429 ||
              response.statusCode >= 500,
          statusCode: response.statusCode,
        );
      }
      if (!expectBody || text.isEmpty) return null;
      final decoded = jsonDecode(text);
      if (decoded is Map<String, Object?> && decoded.containsKey('data')) {
        final code = decoded['code'];
        if (code is num && code.toInt() != 200) {
          throw SyncTransportException(
            'SYNC_REMOTE_${code.toInt()}',
            decoded['msg']?.toString() ?? 'remote synchronization rejected',
            retryable: code.toInt() >= 500,
          );
        }
        return decoded['data'];
      }
      return decoded;
    } on SyncTransportException {
      rethrow;
    } on TimeoutException catch (error) {
      throw SyncTransportException(
        'SYNC_TIMEOUT',
        '$error',
        retryable: true,
      );
    } on SocketException catch (error) {
      throw SyncTransportException(
        'SYNC_NETWORK_UNAVAILABLE',
        error.message,
        retryable: true,
      );
    } on FormatException catch (error) {
      throw SyncTransportException(
        'SYNC_RESPONSE_INVALID',
        '$error',
        retryable: false,
      );
    }
  }

  void close() => _client.close(force: true);
}
