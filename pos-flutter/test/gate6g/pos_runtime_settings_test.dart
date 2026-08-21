import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/app/pos_application_bootstrap.dart';

void main() {
  final publicKey = base64Encode(List<int>.generate(32, (index) => index));

  test('空配置保留失败关闭，完整公开配置可建立正式组合输入', () {
    expect(PosRuntimeSettings.parse(const {}), isNull);
    final settings = PosRuntimeSettings.parse({
      'serverUrl': 'https://pos.synthetic.example/',
      'clientId': 'synthetic-pos-client',
      'loginKey': 'synthetic-rsa-public-key',
      'catalogVersion': '1',
      'promotionVersion': '1',
      'catalogKeys': 'SYNTHETIC_KEY:$publicKey',
      'promotionKeys': 'SYNTHETIC_KEY:$publicKey',
      'industryTemplate': 'CONVENIENCE_V1',
      'returnWarehouseId': '01J60000000000000000000001',
      'configVersion': '1',
      'cashDifferenceMinor': '1000',
    });

    expect(settings, isNotNull);
    expect(settings!.serverUri.scheme, 'https');
    expect(settings.catalogSigningKeys, contains('SYNTHETIC_KEY'));
    expect(settings.cashDifferenceApprovalMinor, 1000);
  });

  test('非回环明文服务端和半配置均失败关闭', () {
    expect(
      () => PosRuntimeSettings.parse({
        'serverUrl': 'http://pos.synthetic.example/',
        'clientId': 'x',
      }),
      throwsA(isA<FormatException>()),
    );
    expect(
      () => PosRuntimeSettings.parse({
        'serverUrl': 'https://pos.synthetic.example/',
      }),
      throwsA(isA<FormatException>()),
    );
  });

  test('退货仓库必须使用与服务端契约一致的规范 ULID', () {
    expect(
      () => PosRuntimeSettings.parse({
        'serverUrl': 'https://pos.synthetic.example/',
        'clientId': 'synthetic-pos-client',
        'loginKey': 'synthetic-rsa-public-key',
        'catalogVersion': '1',
        'promotionVersion': '1',
        'catalogKeys': 'SYNTHETIC_KEY:$publicKey',
        'promotionKeys': 'SYNTHETIC_KEY:$publicKey',
        'industryTemplate': 'CONVENIENCE_V1',
        'returnWarehouseId': '1201',
        'configVersion': '1',
        'cashDifferenceMinor': '1000',
      }),
      throwsA(isA<FormatException>()),
    );
  });

  test('设备秘密默认提供者明确保持 HWD 阻断', () async {
    await expectLater(
      const LockedPosTerminalMaterialProvider().load(),
      throwsA(
        isA<Exception>().having(
          (error) => error.toString(),
          'safe code',
          contains('HWD_SECURE_CREDENTIAL_UNAVAILABLE'),
        ),
      ),
    );
  });
}
