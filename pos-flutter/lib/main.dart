import 'package:flutter/widgets.dart';

import 'app/pos_application_bootstrap.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(await PosApplicationBootstrap.create());
}
