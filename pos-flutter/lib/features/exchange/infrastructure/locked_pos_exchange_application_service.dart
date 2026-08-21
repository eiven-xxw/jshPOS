import '../application/pos_exchange_application_service.dart';
import '../domain/pos_exchange_models.dart';

/// 会话运行时未装配时失败关闭，不伪造换货成功。
final class LockedPosExchangeApplicationService
    implements PosExchangeApplicationService {
  const LockedPosExchangeApplicationService();
  @override
  Future<PosExchangeView> create({
    required PosExchangeSource source,
    required String reasonCode,
  }) => throw const PosExchangeFailure(
    'EXCHANGE_RUNTIME_UNAVAILABLE',
    '换货正式运行时尚未装配。',
  );

  @override
  Future<PosExchangeView> refreshExchange(String exchangeRef) =>
      throw const PosExchangeFailure(
        'EXCHANGE_RUNTIME_UNAVAILABLE',
        '换货正式运行时尚未装配。',
      );

  @override
  Future<PosExchangeView> approve({
    required String exchangeRef,
    required String correlationRef,
    required String reasonCode,
  }) => throw const PosExchangeFailure(
    'EXCHANGE_RUNTIME_UNAVAILABLE',
    '换货正式运行时尚未装配。',
  );

  @override
  Future<PosExchangeView> recover({
    required String exchangeRef,
    required String correlationRef,
    required String targetLeg,
    required String reasonCode,
  }) => throw const PosExchangeFailure(
    'EXCHANGE_RUNTIME_UNAVAILABLE',
    '换货正式运行时尚未装配。',
  );
}
