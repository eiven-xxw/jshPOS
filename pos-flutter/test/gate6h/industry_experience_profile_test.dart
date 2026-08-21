import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/app/jshpos_app.dart';
import 'package:jshpos_pos/features/experience/domain/industry_experience_profile.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

void main() {
  test('三业态版本只映射体验提示且未知版本安全降级', () {
    final convenience = IndustryExperienceProfile.resolve('CONVENIENCE_V1');
    final snack = IndustryExperienceProfile.resolve('SNACK_DISCOUNT_V2');
    final supermarket = IndustryExperienceProfile.resolve(
      'COMMUNITY_SUPERMARKET_V1',
    );
    final unknown = IndustryExperienceProfile.resolve('UNSUPPORTED_V9');

    expect(convenience.industry, PosIndustryExperience.convenience);
    expect(snack.label, '零食折扣模式');
    expect(supermarket.primaryHint, contains('分类搜索'));
    expect(unknown.industry, PosIndustryExperience.safeGeneric);
    expect(unknown.supported, isFalse);
    expect(unknown.checkoutHint, contains('不会猜测'));
  });

  testWidgets('未知行业版本展示可访问的安全通用提示且不解锁终端', (tester) async {
    await tester.pumpWidget(
      const JshposApp(
        industryTemplateVersion: 'UNKNOWN_V1',
        deviceGateway: _UnavailableDeviceGateway(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('终端安全锁定'), findsOneWidget);
    expect(find.byKey(const Key('employeeLoginSubmit')), findsNothing);
  });
}

final class _UnavailableDeviceGateway implements PosDeviceGateway {
  const _UnavailableDeviceGateway();

  @override
  Future<DeviceSnapshot> snapshot() async => const DeviceSnapshot(
    metadata: DeviceMetadata(
      manufacturer: 'synthetic',
      model: 'unavailable',
      androidRelease: '0',
      androidSdk: 0,
      adapterVersion: '1.0',
    ),
    capabilities: <DeviceCapability>{},
  );
}
