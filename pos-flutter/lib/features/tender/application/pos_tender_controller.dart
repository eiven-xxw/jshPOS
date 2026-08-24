import '../domain/pos_tender_models.dart';
import 'pos_tender_application_service.dart';

enum PosTenderPagePhase { ready, submitting, loaded, unknown, failed }

final class PosTenderPageState {
  const PosTenderPageState({
    this.phase = PosTenderPagePhase.ready,
    this.plan,
    this.errorCode,
    this.safeMessage,
    this.recoveryRef,
  });
  final PosTenderPagePhase phase;
  final PosTenderPlanView? plan;
  final String? errorCode;
  final String? safeMessage;

  /// 原计划或冻结订单引用；失败恢复只能继续观察该引用。
  final String? recoveryRef;
  bool get busy => phase == PosTenderPagePhase.submitting;
}

/// 防重复点击并保留原 plan/allocation 身份的页面状态控制器。
final class PosTenderController {
  PosTenderController({required this.service, required this.source});
  final PosTenderApplicationService service;
  final PosTenderSource source;
  PosTenderPageState state = const PosTenderPageState();

  Future<PosTenderPageState> freeze(
    List<PosTenderAllocationDraft> allocations,
  ) async =>
      _run(() => service.freeze(source: source, allocations: allocations));

  Future<PosTenderPageState> collect(
    String allocationRef, {
    int? tenderedMinor,
  }) async => _run(
    () => service.collect(
      planRef: state.plan!.planRef,
      allocationRef: allocationRef,
      tenderedMinor: tenderedMinor,
    ),
  );

  Future<PosTenderPageState> refresh() async {
    final plan = state.plan;
    if (plan == null) return state;
    return _run(() => service.find(plan.planRef));
  }

  Future<PosTenderPageState> _run(
    Future<PosTenderPlanView> Function() operation,
  ) async {
    if (state.busy) return state;
    final previous = state.plan;
    state = PosTenderPageState(
      phase: PosTenderPagePhase.submitting,
      plan: previous,
    );
    try {
      final plan = await operation();
      return state = PosTenderPageState(
        phase: PosTenderPagePhase.loaded,
        plan: plan,
      );
    } on PosTenderFailure catch (error) {
      return state = PosTenderPageState(
        phase: error.resultUnknown
            ? PosTenderPagePhase.unknown
            : PosTenderPagePhase.failed,
        plan: previous,
        errorCode: error.code,
        safeMessage: error.safeMessage,
        recoveryRef: error.planRef ?? previous?.planRef ?? source.orderRef,
      );
    }
  }
}
