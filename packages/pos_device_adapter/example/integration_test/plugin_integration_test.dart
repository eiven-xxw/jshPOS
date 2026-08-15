import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('native adapter negotiates contract 1.0', (tester) async {
    final snapshot = await const PosDeviceAdapter().snapshot();
    expect(snapshot.metadata.model, isNotEmpty);
  });
}
