# Flutter POS

Android-only POS application shell for the T0 technical baseline.

- Flutter 3.47.0 / Dart 3.13.0
- Android 9 (API 28) compatibility floor
- Android 11+ for newly certified commercial devices
- Hardware access only through `packages/pos_device_adapter`
- No checkout, order, payment, inventory, or offline business behavior in T0

Run `flutter pub get`, `flutter analyze --fatal-infos`, and `flutter test` before
submitting a change. Release signing is supplied only by the protected delivery
pipeline.
