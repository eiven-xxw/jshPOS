# Flutter POS

Android-only POS application with the admitted Gate 2 local cash transaction core.

- Flutter 3.47.0 / Dart 3.13.0
- Android 9 (API 28) compatibility floor
- Android 11+ for newly certified commercial devices
- Hardware access only through `packages/pos_device_adapter`
- Formal SQLite shift, basket, cash-order, print queue, Outbox, idempotency and audit transaction boundary
- Trusted tenant/store/terminal/cashier binding is mandatory before local data can open
- No third-party payment, refund, inventory, promotion or remote synchronization runtime in Gate 2
- The app shell remains locked until trusted activation and a verified catalog/configuration package are supplied

Run `flutter pub get`, `flutter analyze --fatal-infos`, and `flutter test` before
submitting a change. Release signing is supplied only by the protected delivery
pipeline.
