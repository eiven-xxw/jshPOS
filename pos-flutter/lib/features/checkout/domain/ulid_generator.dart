import 'dart:math';

final class UlidGenerator {
  UlidGenerator({Random? random, DateTime Function()? now})
    : _random = random ?? Random.secure(),
      _now = now ?? DateTime.now;

  static const _alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  final Random _random;
  final DateTime Function() _now;

  String next() {
    var timestamp = _now().millisecondsSinceEpoch;
    if (timestamp < 0 || timestamp > 0xffffffffffff) {
      throw StateError('ORD-ID-001: timestamp outside ULID range');
    }
    final result = List<String>.filled(26, '0');
    for (var index = 9; index >= 0; index--) {
      result[index] = _alphabet[timestamp & 31];
      timestamp ~/= 32;
    }
    for (var index = 10; index < 26; index++) {
      result[index] = _alphabet[_random.nextInt(32)];
    }
    return result.join();
  }

  static bool isCanonical(String value) =>
      RegExp(r'^[0-9A-HJKMNP-TV-Z]{26}$').hasMatch(value);
}
