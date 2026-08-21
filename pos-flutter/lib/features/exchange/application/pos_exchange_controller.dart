import '../domain/pos_exchange_models.dart';
import 'pos_exchange_application_service.dart';

enum PosExchangePagePhase { ready, submitting, unknown, loaded, failed }

/// 换货页面状态；保留原 exchangeRef，UNKNOWN 后不再开放创建。
final class PosExchangePageState {
  const PosExchangePageState({
    this.phase = PosExchangePagePhase.ready,
    this.view,
    this.errorCode,
    this.safeMessage,
    this.recoverableExchangeRef,
  });
  final PosExchangePagePhase phase;
  final PosExchangeView? view;
  final String? errorCode;
  final String? safeMessage;
  final String? recoverableExchangeRef;
  bool get busy => phase == PosExchangePagePhase.submitting;
}

/// 页面只发送“建立关联/查询原关联”意图，SQLite 与 HTTP 均封装在应用服务后。
final class PosExchangeController {
  PosExchangeController({required this.service, required this.source});
  final PosExchangeApplicationService service;
  final PosExchangeSource source;
  PosExchangePageState state = const PosExchangePageState();

  Future<PosExchangePageState> create(String reasonCode) async {
    if (state.busy || state.recoverableExchangeRef != null) return state;
    state = const PosExchangePageState(phase: PosExchangePagePhase.submitting);
    try {
      final view = await service.create(source: source, reasonCode: reasonCode);
      return state = PosExchangePageState(
        phase: PosExchangePagePhase.loaded,
        view: view,
      );
    } on PosExchangeFailure catch (error) {
      return state = PosExchangePageState(
        phase: error.resultUnknown
            ? PosExchangePagePhase.unknown
            : PosExchangePagePhase.failed,
        errorCode: error.code,
        safeMessage: error.safeMessage,
        recoverableExchangeRef: error.exchangeRef,
      );
    }
  }

  Future<PosExchangePageState> refresh([String? exchangeRef]) async {
    final ref =
        exchangeRef ?? state.view?.exchangeRef ?? state.recoverableExchangeRef;
    if (state.busy || ref == null) return state;
    state = PosExchangePageState(
      phase: PosExchangePagePhase.submitting,
      view: state.view,
      recoverableExchangeRef: ref,
    );
    try {
      final view = await service.refreshExchange(ref);
      return state = PosExchangePageState(
        phase: PosExchangePagePhase.loaded,
        view: view,
      );
    } on PosExchangeFailure catch (error) {
      return state = PosExchangePageState(
        phase: error.resultUnknown
            ? PosExchangePagePhase.unknown
            : PosExchangePagePhase.failed,
        view: state.view,
        errorCode: error.code,
        safeMessage: error.safeMessage,
        recoverableExchangeRef: ref,
      );
    }
  }

  Future<PosExchangePageState> approve(String reasonCode) => _mutate(
    (view) => service.approve(
      exchangeRef: view.exchangeRef,
      correlationRef: view.correlationRef,
      reasonCode: reasonCode,
    ),
  );

  Future<PosExchangePageState> recover(String targetLeg, String reasonCode) =>
      _mutate(
        (view) => service.recover(
          exchangeRef: view.exchangeRef,
          correlationRef: view.correlationRef,
          targetLeg: targetLeg,
          reasonCode: reasonCode,
        ),
      );

  Future<PosExchangePageState> _mutate(
    Future<PosExchangeView> Function(PosExchangeView view) operation,
  ) async {
    final current = state.view;
    if (state.busy || current == null) return state;
    state = PosExchangePageState(
      phase: PosExchangePagePhase.submitting,
      view: current,
      recoverableExchangeRef: current.exchangeRef,
    );
    try {
      final view = await operation(current);
      return state = PosExchangePageState(
        phase: PosExchangePagePhase.loaded,
        view: view,
      );
    } on PosExchangeFailure catch (error) {
      return state = PosExchangePageState(
        phase: error.resultUnknown
            ? PosExchangePagePhase.unknown
            : PosExchangePagePhase.failed,
        view: current,
        errorCode: error.code,
        safeMessage: error.safeMessage,
        recoverableExchangeRef: current.exchangeRef,
      );
    }
  }
}
