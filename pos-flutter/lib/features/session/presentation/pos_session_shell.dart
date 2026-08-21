import 'package:flutter/material.dart';

import '../../sale/application/pos_sale_application_service.dart';
import '../../sale/application/pos_sale_controller.dart';
import '../../sale/presentation/pos_checkout_page.dart';
import '../../experience/domain/industry_experience_profile.dart';
import '../../exchange/application/pos_exchange_application_service.dart';
import '../../exchange/application/pos_exchange_controller.dart';
import '../../exchange/domain/pos_exchange_models.dart';
import '../../exchange/presentation/pos_exchange_page.dart';
import '../../return_refund/application/pos_return_application_service.dart';
import '../../return_refund/application/pos_return_controller.dart';
import '../../return_refund/domain/pos_return_models.dart';
import '../../return_refund/presentation/pos_return_page.dart';
import '../../shift/application/pos_shift_application_service.dart';
import '../../shift/presentation/pos_cash_management_page.dart';
import '../application/pos_session_service.dart';
import '../domain/pos_session_models.dart';

/// POS-007 正式应用壳；只展示会话服务快照并向应用服务发送命令。
final class PosSessionShell extends StatefulWidget {
  const PosSessionShell({
    required this.industryTemplateVersion,
    required this.sessionService,
    required this.saleService,
    required this.returnService,
    required this.exchangeService,
    required this.shiftService,
    super.key,
  });

  final PosSessionService sessionService;
  final String industryTemplateVersion;
  final PosSaleApplicationService saleService;
  final PosReturnApplicationService returnService;
  final PosExchangeApplicationService exchangeService;
  final PosShiftApplicationService shiftService;

  @override
  State<PosSessionShell> createState() => _PosSessionShellState();
}

class _PosSessionShellState extends State<PosSessionShell> {
  final _loginController = TextEditingController();
  final _secretController = TextEditingController();
  final _secretFocus = FocusNode();

  PosSessionState get _state => widget.sessionService.state;
  IndustryExperienceProfile get _experience =>
      IndustryExperienceProfile.resolve(widget.industryTemplateVersion);

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  @override
  void dispose() {
    _loginController.dispose();
    _secretController.dispose();
    _secretFocus.dispose();
    super.dispose();
  }

  Future<void> _bootstrap() async {
    await widget.sessionService.bootstrap();
    if (mounted) setState(() {});
  }

  Future<void> _login() async {
    if (_state.phase == PosSessionPhase.authenticating) return;
    await widget.sessionService.login(
      loginName: _loginController.text,
      secret: _secretController.text,
    );
    _secretController.clear();
    if (mounted) setState(() {});
  }

  Future<void> _logout() async {
    await widget.sessionService.logout();
    _loginController.clear();
    _secretController.clear();
    if (mounted) setState(() {});
  }

  Future<void> _openShift() async {
    final terminal = _state.terminal;
    final employee = _state.employee;
    if (terminal == null || employee == null) return;
    final openingCash = await _cashDialog(
      title: '开启班次',
      label: '备用金（元）',
      confirm: '确认开班',
    );
    if (openingCash == null || !mounted) return;
    try {
      final shift = await widget.shiftService.open(
        businessDate: terminal.businessDate,
        openingCash: openingCash,
        idempotencyKey:
            'open:${terminal.terminalId}:${terminal.businessDate}:${employee.employeeId}',
      );
      widget.sessionService.acceptOpenedShift(shift);
      if (mounted) setState(() {});
    } on PosSessionFailure catch (error) {
      _showFailure(error);
    } catch (_) {
      _showFailure(
        const PosSessionFailure('SHIFT_OPEN_FAILED', '开班失败，请核对金额和班次状态。'),
      );
    }
  }

  Future<void> _closeShift() async {
    final shift = _state.shift;
    if (shift == null) return;
    final actualCash = await _cashDialog(
      title: '关闭班次',
      label: '实点现金（元）',
      confirm: '确认关班',
    );
    if (actualCash == null || !mounted) return;
    try {
      await widget.shiftService.close(
        shiftId: shift.shiftId,
        actualCash: actualCash,
        idempotencyKey: 'close:${shift.shiftId}:$actualCash',
      );
      widget.sessionService.acceptClosedShift(shift.shiftId);
      if (mounted) setState(() {});
    } on PosSessionFailure catch (error) {
      _showFailure(error);
    } catch (_) {
      _showFailure(
        const PosSessionFailure('SHIFT_CLOSE_FAILED', '关班失败；差异超限时须由主管完成独立审批。'),
      );
    }
  }

  Future<String?> _cashDialog({
    required String title,
    required String label,
    required String confirm,
  }) async {
    var input = '';
    return showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(title),
        content: TextField(
          key: Key('$title-cash'),
          autofocus: true,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          onChanged: (value) => input = value.trim(),
          decoration: InputDecoration(
            labelText: label,
            prefixText: '¥ ',
            border: const OutlineInputBorder(),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('取消'),
          ),
          FilledButton(
            key: Key('$title-submit'),
            onPressed: () {
              if (input.isNotEmpty) Navigator.pop(dialogContext, input);
            },
            child: Text(confirm),
          ),
        ],
      ),
    );
  }

  void _showFailure(PosSessionFailure error) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text('${error.message}（${error.code}）')));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('鲸熵汇收银系统'),
        actions: [
          if (_state.employee != null)
            Padding(
              padding: const EdgeInsets.only(right: 12),
              child: TextButton.icon(
                key: const Key('secureLogout'),
                onPressed: _state.phase == PosSessionPhase.signingOut
                    ? null
                    : _logout,
                icon: const Icon(Icons.logout),
                label: const Text('安全退出'),
              ),
            ),
        ],
      ),
      body: SafeArea(child: _body(context)),
    );
  }

  Widget _body(BuildContext context) {
    switch (_state.phase) {
      case PosSessionPhase.bootstrapping:
        return const _CenteredPanel(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 20),
              Text('正在校验终端可信身份…'),
            ],
          ),
        );
      case PosSessionPhase.locked:
        return _CenteredPanel(
          child: _LockedCard(
            code: _state.errorCode ?? 'TERMINAL_UNTRUSTED',
            message: _state.safeMessage ?? '终端已锁定。',
            onRetry: _bootstrap,
          ),
        );
      case PosSessionPhase.signedOut:
      case PosSessionPhase.authenticating:
        return _CenteredPanel(child: _loginCard(context));
      case PosSessionPhase.readyNoShift:
      case PosSessionPhase.readyWithShift:
      case PosSessionPhase.signingOut:
        return _sessionHome(context);
    }
  }

  Widget _loginCard(BuildContext context) {
    final busy = _state.phase == PosSessionPhase.authenticating;
    final terminal = _state.terminal!;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 520),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: AutofillGroup(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Icon(
                  Icons.point_of_sale,
                  size: 64,
                  color: Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(height: 16),
                Text(
                  '${terminal.storeName} · ${terminal.terminalName}',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 8),
                Text(
                  '业务日 ${terminal.businessDate} · ${terminal.storeTimezone}',
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 12),
                _IndustryExperienceBanner(profile: _experience),
                if (_state.safeMessage != null) ...[
                  const SizedBox(height: 16),
                  _SafeError(
                    code: _state.errorCode ?? 'AUTH_FAILED',
                    message: _state.safeMessage!,
                  ),
                ],
                const SizedBox(height: 20),
                TextField(
                  key: const Key('employeeLogin'),
                  controller: _loginController,
                  enabled: !busy,
                  autofocus: true,
                  autofillHints: const [AutofillHints.username],
                  textInputAction: TextInputAction.next,
                  onSubmitted: (_) => _secretFocus.requestFocus(),
                  decoration: const InputDecoration(
                    labelText: '员工工号',
                    prefixIcon: Icon(Icons.badge_outlined),
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 16),
                TextField(
                  key: const Key('employeeSecret'),
                  controller: _secretController,
                  focusNode: _secretFocus,
                  enabled: !busy,
                  obscureText: true,
                  enableSuggestions: false,
                  autocorrect: false,
                  autofillHints: const [AutofillHints.password],
                  textInputAction: TextInputAction.done,
                  onSubmitted: (_) => _login(),
                  decoration: const InputDecoration(
                    labelText: '登录口令',
                    prefixIcon: Icon(Icons.lock_outline),
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 20),
                SizedBox(
                  height: 56,
                  child: FilledButton.icon(
                    key: const Key('employeeLoginSubmit'),
                    onPressed: busy ? null : _login,
                    icon: busy
                        ? const SizedBox.square(
                            dimension: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.login),
                    label: Text(busy ? '正在验证…' : '登录收银台'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _sessionHome(BuildContext context) {
    final terminal = _state.terminal!;
    final employee = _state.employee!;
    final shift = _state.shift;
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        if (_state.safeMessage != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 16),
            child: _SafeError(
              code: _state.errorCode ?? 'SESSION_WARNING',
              message: _state.safeMessage!,
            ),
          ),
        Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            _ContextCard(
              icon: Icons.storefront,
              title: terminal.storeName,
              subtitle: '${terminal.tenantName} · ${terminal.storeTimezone}',
            ),
            _ContextCard(
              icon: Icons.point_of_sale,
              title: terminal.terminalName,
              subtitle: '协议 ${terminal.protocolVersion}',
            ),
            _ContextCard(
              icon: Icons.person,
              title: employee.employeeName,
              subtitle: employee.roles.join(' / '),
            ),
            _ContextCard(
              icon: shift == null ? Icons.lock_clock : Icons.schedule,
              title: shift == null ? '未开班' : '班次进行中',
              subtitle: '业务日 ${terminal.businessDate}',
            ),
          ],
        ),
        const SizedBox(height: 16),
        _IndustryExperienceBanner(profile: _experience),
        const SizedBox(height: 24),
        Text('可用工作区', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 12),
        GridView.count(
          crossAxisCount: MediaQuery.sizeOf(context).width >= 900 ? 3 : 2,
          mainAxisSpacing: 12,
          crossAxisSpacing: 12,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          childAspectRatio: 2.2,
          children: [
            _PermissionTile(
              label: shift == null ? '开启班次' : '收银工作台',
              icon: Icons.shopping_cart_checkout,
              enabled: shift == null
                  ? _state.hasPermission(PosPermission.shiftOpen)
                  : _state.hasPermission(PosPermission.saleOperate),
              onTap: shift == null
                  ? _openShift
                  : () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => PosCheckoutPage(
                          controller: PosSaleController(
                            sessionService: widget.sessionService,
                            saleService: widget.saleService,
                          ),
                        ),
                      ),
                    ),
            ),
            _PermissionTile(
              label: '关闭班次',
              icon: Icons.lock_clock,
              enabled:
                  shift != null &&
                  _state.hasPermission(PosPermission.shiftClose),
              onTap: shift == null ? null : _closeShift,
            ),
            _PermissionTile(
              label: '班次现金与钱箱',
              icon: Icons.account_balance_wallet_outlined,
              enabled:
                  shift != null &&
                  (_state.hasPermission(PosPermission.cashManage) ||
                      _state.hasPermission(PosPermission.drawerNoSale)),
              onTap: shift == null
                  ? null
                  : () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => PosCashManagementPage(
                          shiftId: shift.shiftId,
                          service: widget.shiftService,
                          allowCashMovement: _state.hasPermission(
                            PosPermission.cashManage,
                          ),
                          allowDrawerRequest: _state.hasPermission(
                            PosPermission.drawerNoSale,
                          ),
                        ),
                      ),
                    ),
            ),
            _PermissionTile(
              label: '原单退货退款',
              icon: Icons.assignment_return,
              enabled:
                  shift != null &&
                  _state.hasPermission(PosPermission.returnRead) &&
                  _state.hasPermission(PosPermission.returnCreate),
              onTap: shift == null
                  ? null
                  : () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => PosReturnPage(
                          controller: PosReturnController(
                            sessionService: widget.sessionService,
                            returnService: widget.returnService,
                          ),
                          onStartExchange:
                              _state.hasPermission(
                                    PosPermission.exchangeRead,
                                  ) &&
                                  _state.hasPermission(
                                    PosPermission.exchangeCreate,
                                  )
                              ? (submission) =>
                                    _openExchangeCheckout(context, submission)
                              : null,
                        ),
                      ),
                    ),
            ),
            _PermissionTile(
              label: '同步状态',
              icon: Icons.sync,
              enabled: _state.hasPermission(PosPermission.syncView),
              onTap: () => _openCheckout(context),
            ),
            _PermissionTile(
              label: '打印任务预览',
              icon: Icons.receipt_long,
              enabled: _state.hasPermission(PosPermission.printPreview),
              onTap: () => _openCheckout(context),
            ),
          ],
        ),
        const SizedBox(height: 20),
        const Text(
          '当前为内部产品化软件验证。真实支付、真实打印与实机认证尚未开放。',
          textAlign: TextAlign.center,
        ),
      ],
    );
  }

  Future<void> _openCheckout(BuildContext context) =>
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => PosCheckoutPage(
            controller: PosSaleController(
              sessionService: widget.sessionService,
              saleService: widget.saleService,
            ),
          ),
        ),
      );

  Future<void> _openExchangeCheckout(
    BuildContext context,
    PosReturnSubmissionView originalReturn,
  ) => Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) => PosCheckoutPage(
        controller: PosSaleController(
          sessionService: widget.sessionService,
          saleService: widget.saleService,
        ),
        onExchangeSettlement: (newSale) => Navigator.of(context).push(
          MaterialPageRoute<void>(
            builder: (_) => PosExchangePage(
              controller: PosExchangeController(
                service: widget.exchangeService,
                source: PosExchangeSource(
                  originalReturn: originalReturn,
                  newSale: newSale,
                ),
              ),
              allowApprove: _state.hasPermission(PosPermission.exchangeApprove),
              allowRecover: _state.hasPermission(PosPermission.exchangeRecover),
            ),
          ),
        ),
      ),
    ),
  );
}

class _CenteredPanel extends StatelessWidget {
  const _CenteredPanel({required this.child});
  final Widget child;
  @override
  Widget build(BuildContext context) => Center(
    child: SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: child,
    ),
  );
}

class _LockedCard extends StatelessWidget {
  const _LockedCard({
    required this.code,
    required this.message,
    required this.onRetry,
  });
  final String code;
  final String message;
  final Future<void> Function() onRetry;
  @override
  Widget build(BuildContext context) => ConstrainedBox(
    constraints: const BoxConstraints(maxWidth: 560),
    child: Card(
      child: Padding(
        padding: const EdgeInsets.all(28),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.phonelink_lock, size: 72, color: Colors.red),
            const SizedBox(height: 16),
            Text('终端安全锁定', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 12),
            _SafeError(code: code, message: message),
            const SizedBox(height: 20),
            SizedBox(
              height: 52,
              child: FilledButton.tonalIcon(
                key: const Key('retryTerminalVerification'),
                onPressed: onRetry,
                icon: const Icon(Icons.refresh),
                label: const Text('重新校验'),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

class _SafeError extends StatelessWidget {
  const _SafeError({required this.code, required this.message});
  final String code;
  final String message;
  @override
  Widget build(BuildContext context) => Semantics(
    liveRegion: true,
    child: Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Text('$message\n错误码：$code'),
    ),
  );
}

class _ContextCard extends StatelessWidget {
  const _ContextCard({
    required this.icon,
    required this.title,
    required this.subtitle,
  });
  final IconData icon;
  final String title;
  final String subtitle;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: 260,
    child: Card(
      child: ListTile(
        minVerticalPadding: 16,
        leading: Icon(icon, size: 32),
        title: Text(title),
        subtitle: Text(subtitle),
      ),
    ),
  );
}

class _PermissionTile extends StatelessWidget {
  const _PermissionTile({
    required this.label,
    required this.icon,
    required this.enabled,
    required this.onTap,
  });
  final String label;
  final IconData icon;
  final bool enabled;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) => Card(
    color: enabled ? null : Theme.of(context).disabledColor.withAlpha(20),
    child: InkWell(
      onTap: enabled ? onTap : null,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(icon, size: 34),
            const SizedBox(width: 12),
            Expanded(child: Text(label)),
            Icon(enabled ? Icons.chevron_right : Icons.lock_outline),
          ],
        ),
      ),
    ),
  );
}

/// 只读行业体验提示；不会参与授权或任何领域计算。
class _IndustryExperienceBanner extends StatelessWidget {
  const _IndustryExperienceBanner({required this.profile});

  final IndustryExperienceProfile profile;

  @override
  Widget build(BuildContext context) => Semantics(
    label: '当前行业体验：${profile.label}',
    liveRegion: !profile.supported,
    child: Container(
      key: const Key('industryExperienceBanner'),
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: profile.supported
            ? Theme.of(context).colorScheme.primaryContainer
            : Theme.of(context).colorScheme.errorContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(profile.supported ? Icons.storefront : Icons.warning_amber),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  profile.label,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 2),
                Text('${profile.primaryHint}；${profile.checkoutHint}'),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}
