import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:pointycastle/asn1.dart';
import 'package:pointycastle/export.dart';

/// RuoYi `@ApiEncrypt` 请求载荷；encryptKey 和 body 均只在请求调用栈中存在。
final class EncryptedApiRequest {
  const EncryptedApiRequest({required this.encryptKey, required this.body});

  final String encryptKey;
  final String body;
}

/// 登录请求加密端口，便于在测试中验证协议且不让页面接触密码学细节。
abstract interface class ApiRequestEncryptor {
  EncryptedApiRequest encryptJson(Map<String, Object?> value);
}

/// 与 RuoYi-Vue-Plus AES-ECB/PKCS7 + RSA-PKCS1 v1.5 请求协议兼容的实现。
final class RuoYiApiRequestEncryptor implements ApiRequestEncryptor {
  RuoYiApiRequestEncryptor(this.rsaPublicKeyBase64)
    : _publicKey = _parsePublicKey(rsaPublicKeyBase64);

  final String rsaPublicKeyBase64;
  final RSAPublicKey _publicKey;
  final Random _random = Random.secure();

  @override
  EncryptedApiRequest encryptJson(Map<String, Object?> value) {
    final aesKey = Uint8List.fromList(
      List<int>.generate(
        32,
        (_) => _alphabet.codeUnitAt(_random.nextInt(_alphabet.length)),
      ),
    );
    final aes = PaddedBlockCipher('AES/ECB/PKCS7')
      ..init(true, PaddedBlockCipherParameters(KeyParameter(aesKey), null));
    final encryptedBody = aes.process(
      Uint8List.fromList(utf8.encode(jsonEncode(value))),
    );

    final rsa = PKCS1Encoding(RSAEngine())
      ..init(true, PublicKeyParameter<RSAPublicKey>(_publicKey));
    final encodedKey = base64Encode(aesKey);
    final encryptedKey = rsa.process(
      Uint8List.fromList(utf8.encode(encodedKey)),
    );
    return EncryptedApiRequest(
      encryptKey: base64Encode(encryptedKey),
      body: base64Encode(encryptedBody),
    );
  }
}

const _alphabet =
    'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';

RSAPublicKey _parsePublicKey(String source) {
  try {
    final top = ASN1Parser(base64Decode(source)).nextObject() as ASN1Sequence;
    final bitString = top.elements![1] as ASN1BitString;
    final keySequence =
        ASN1Parser(Uint8List.fromList(bitString.stringValues!)).nextObject()
            as ASN1Sequence;
    final modulus = (keySequence.elements![0] as ASN1Integer).integer;
    final exponent = (keySequence.elements![1] as ASN1Integer).integer;
    if (modulus == null || exponent == null) throw const FormatException();
    return RSAPublicKey(modulus, exponent);
  } catch (_) {
    throw const FormatException('AUTH_RSA_PUBLIC_KEY_INVALID');
  }
}
