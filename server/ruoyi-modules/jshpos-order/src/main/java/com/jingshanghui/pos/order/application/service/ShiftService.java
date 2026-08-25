package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.application.model.OrderCommands.ApproveDifference;
import com.jingshanghui.pos.order.application.model.OrderCommands.CloseShift;
import com.jingshanghui.pos.order.application.model.OrderCommands.CloseSyncedShift;
import com.jingshanghui.pos.order.application.model.OrderCommands.OpenShift;
import com.jingshanghui.pos.order.application.model.OrderCommands.OpenSyncedShift;
import com.jingshanghui.pos.order.application.model.OrderCommands.RecordCashMovement;
import com.jingshanghui.pos.order.application.model.OrderCommands.RequestNoSaleDrawer;
import com.jingshanghui.pos.order.application.model.OrderViews.ApprovalView;
import com.jingshanghui.pos.order.application.model.OrderViews.CashMovementView;
import com.jingshanghui.pos.order.application.model.OrderViews.DrawerEventView;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import com.jingshanghui.pos.order.application.port.ShiftSubmissionPort;
import com.jingshanghui.pos.order.domain.CanonicalHash;
import com.jingshanghui.pos.order.domain.OrderRules;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ShiftService implements ShiftSubmissionPort {

    private static final String OPEN = "OPEN_SHIFT";
    private static final String CLOSE = "CLOSE_SHIFT";
    private static final String APPROVE = "APPROVE_SHIFT_DIFFERENCE";
    private static final String CASH_MOVEMENT = "RECORD_SHIFT_CASH_MOVEMENT";
    private static final String DRAWER_REQUEST = "REQUEST_NO_SALE_DRAWER";
    private static final Set<String> CASH_MOVEMENT_TYPES = Set.of("CASH_IN", "CASH_OUT", "SAFE_DROP");

    private final OrderMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final IdempotencyService idempotency;
    private final OrderJournalService journal;
    private final ShiftDifferencePolicy differencePolicy;
    private final UlidGenerator ulids;
    private final Clock clock;

    @Transactional
    public ShiftView open(OpenShift command) {
        return openInternal(new OpenInput(command.commandId(), command.idempotencyKey(), ulids.next(),
            command.storeId(), command.terminalId(), command.cashierId(), command.businessDate(),
            command.storeTimezone(), command.openingCashMinor(), command.configVersion(), command.occurredAt()));
    }

    /** 接收 POS 已冻结的班次身份，确保本地与云端使用同一个 shiftId。 */
    @Override
    @Transactional
    public ShiftView openSynced(OpenSyncedShift command) {
        OrderRules.requireUlid(command.shiftId(), "shiftId");
        return openInternal(new OpenInput(command.commandId(), command.idempotencyKey(), command.shiftId(),
            command.storeId(), command.terminalId(), command.cashierId(), command.businessDate(),
            command.storeTimezone(), command.openingCashMinor(), command.configVersion(), command.occurredAt()));
    }

    private ShiftView openInternal(OpenInput command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireActor(command.cashierId(), principal);
        authorizationService.requireStoreAccess(command.storeId());
        OrderRules.requireUlid(command.commandId(), "commandId");
        OrderRules.requireUlid(command.terminalId(), "terminalId");
        OrderRules.requireIdempotencyKey(command.idempotencyKey());
        OrderRules.requireMoney(command.openingCashMinor(), "openingCashMinor");
        if (command.businessDate() == null || command.occurredAt() == null || command.configVersion() <= 0
            || command.storeTimezone() == null || command.storeTimezone().isBlank()) {
            throw new ServiceException("SHIFT_INPUT_INVALID: 开班上下文不完整", 400);
        }
        String tenantId = principal.tenantId();
        String requestHash = CanonicalHash.sha256(CanonicalHash.lengthPrefixed(List.of(command.storeId(),
            command.terminalId(), principal.userId(), command.businessDate(), command.storeTimezone(),
            command.openingCashMinor(), command.configVersion())));
        ShiftView duplicate = idempotency.find(tenantId, OPEN, command.idempotencyKey(), requestHash, ShiftView.class);
        if (duplicate != null) {
            return duplicate;
        }
        String shiftId = command.shiftId();
        LocalDateTime at = utc(command.occurredAt());
        mapper.insertShift(tenantId, shiftId, command.storeId(), command.terminalId(), principal.userId(),
            safeName(principal), command.businessDate(), command.storeTimezone(), command.configVersion(),
            command.openingCashMinor(), at);
        ShiftView result = requireShift(tenantId, shiftId);
        String payload = CanonicalJson.from(Map.<String, Object>of("shiftId", shiftId, "storeId", command.storeId().toString(),
            "terminalId", command.terminalId(), "cashierId", principal.userId().toString(),
            "businessDate", command.businessDate().toString(), "storeTimezone", command.storeTimezone(),
            "currency", "CNY", "openingCashMinor", command.openingCashMinor())).json();
        journal.appendEvent(tenantId, "shift.event", "shift.opened.v1", "SHIFT", shiftId, 1,
            command.commandId(), payload, at);
        journal.audit(tenantId, "SHIFT_OPENED", "SHIFT", shiftId, principal.userId(), null,
            command.commandId(), null, "OPEN", command.openingCashMinor(), requestHash, "OPEN_SHIFT", at);
        idempotency.save(tenantId, OPEN, command.commandId(), command.idempotencyKey(), requestHash,
            shiftId, result, at);
        return result;
    }

    /** 内部统一开班输入，避免 REST 开班与同步开班复制状态机。 */
    private record OpenInput(String commandId, String idempotencyKey, String shiftId, Long storeId,
                             String terminalId, String cashierId, LocalDate businessDate,
                             String storeTimezone, long openingCashMinor, long configVersion,
                             Instant occurredAt) { }

    /**
     * 追加班次非销售现金事实，并在同一事务中更新理论现金、审计、Outbox 和幂等结果。
     */
    @Override
    @Transactional
    public CashMovementView recordCashMovement(RecordCashMovement command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validateOperationIdentity(command.commandId(), command.idempotencyKey(), command.movementId(),
            command.shiftId(), command.expectedVersion(), command.reasonCode(), command.reasonText(),
            command.authorizationRef(), command.occurredAt());
        OrderRules.requireMoney(command.amountMinor(), "amountMinor");
        if (command.amountMinor() <= 0 || !CASH_MOVEMENT_TYPES.contains(command.movementType())) {
            throw new ServiceException("SHIFT_CASH_INPUT_INVALID: 现金类型或金额无效", 400);
        }
        String tenantId = principal.tenantId();
        String requestHash = CanonicalHash.sha256(CanonicalHash.lengthPrefixed(List.of(command.movementId(),
            command.shiftId(), command.movementType(), command.amountMinor(), command.expectedVersion(),
            command.reasonCode(), command.reasonText(), command.authorizationRef())));
        CashMovementView duplicate = idempotency.find(tenantId, CASH_MOVEMENT, command.idempotencyKey(),
            requestHash, CashMovementView.class);
        if (duplicate != null) return duplicate;
        ShiftView shift = lockOwnedOpenShift(principal, command.shiftId(), command.expectedVersion());
        long signedAmount = "CASH_IN".equals(command.movementType())
            ? command.amountMinor() : -command.amountMinor();
        long theoretical = safeAdd(shift.theoreticalCashMinor(), signedAmount);
        long version = shift.recordVersion() + 1;
        LocalDateTime at = utc(command.occurredAt());
        mapper.insertCashMovement(tenantId, command.movementId(), shift.shiftId(), shift.storeId(),
            shift.terminalId(), principal.userId(), shift.businessDate(), command.movementType(), signedAmount,
            command.reasonCode(), command.reasonText().trim(), command.authorizationRef(), command.commandId(),
            requestHash, version, at);
        if (mapper.applyNonSaleCash(tenantId, shift.shiftId(), theoretical, shift.recordVersion()) != 1) {
            throw new ServiceException("SHIFT_STATE_CONFLICT: 班次现金并发冲突", 409);
        }
        CashMovementView result = new CashMovementView(command.movementId(), shift.shiftId(),
            command.movementType(), signedAmount, "CNY", shift.businessDate(), theoretical, version);
        String payload = CanonicalJson.from(Map.<String, Object>ofEntries(
            Map.entry("movementId", command.movementId()), Map.entry("shiftId", shift.shiftId()),
            Map.entry("storeId", shift.storeId().toString()), Map.entry("terminalId", shift.terminalId()),
            Map.entry("cashierId", principal.userId().toString()),
            Map.entry("businessDate", shift.businessDate().toString()),
            Map.entry("movementType", command.movementType()), Map.entry("amountMinor", command.amountMinor()),
            Map.entry("signedAmountMinor", signedAmount),
            Map.entry("currency", "CNY"), Map.entry("reasonCode", command.reasonCode()),
            Map.entry("reasonText", command.reasonText().trim()), Map.entry("expectedVersion", command.expectedVersion())
        )).json();
        journal.appendEvent(tenantId, "shift.event", "shift.cash-movement.recorded.v1", "SHIFT",
            shift.shiftId(), version, command.commandId(), payload, at);
        journal.audit(tenantId, "SHIFT_CASH_" + command.movementType(), "SHIFT", shift.shiftId(),
            principal.userId(), null, command.commandId(), "OPEN", "OPEN", signedAmount, requestHash,
            command.reasonCode(), at);
        idempotency.save(tenantId, CASH_MOVEMENT, command.commandId(), command.idempotencyKey(), requestHash,
            shift.shiftId(), result, at);
        return result;
    }

    /** 钱箱事件仅落审计事实，真实设备执行固定失败关闭。 */
    @Override
    @Transactional
    public DrawerEventView requestNoSaleDrawer(RequestNoSaleDrawer command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validateOperationIdentity(command.commandId(), command.idempotencyKey(), command.drawerEventId(),
            command.shiftId(), command.expectedVersion(), command.reasonCode(), command.reasonText(),
            command.authorizationRef(), command.occurredAt());
        String tenantId = principal.tenantId();
        String requestHash = CanonicalHash.sha256(CanonicalHash.lengthPrefixed(List.of(command.drawerEventId(),
            command.shiftId(), command.expectedVersion(), command.reasonCode(), command.reasonText(),
            command.authorizationRef())));
        DrawerEventView duplicate = idempotency.find(tenantId, DRAWER_REQUEST, command.idempotencyKey(),
            requestHash, DrawerEventView.class);
        if (duplicate != null) return duplicate;
        ShiftView shift = lockOwnedOpenShift(principal, command.shiftId(), command.expectedVersion());
        long version = shift.recordVersion() + 1;
        LocalDateTime at = utc(command.occurredAt());
        mapper.insertDrawerEvent(tenantId, command.drawerEventId(), shift.shiftId(), shift.storeId(),
            shift.terminalId(), principal.userId(), shift.businessDate(), command.reasonCode(),
            command.reasonText().trim(), command.authorizationRef(), command.commandId(), requestHash, version, at);
        if (mapper.advanceShiftVersion(tenantId, shift.shiftId(), shift.recordVersion()) != 1) {
            throw new ServiceException("SHIFT_STATE_CONFLICT: 钱箱请求并发冲突", 409);
        }
        DrawerEventView result = new DrawerEventView(command.drawerEventId(), shift.shiftId(),
            "NO_SALE_OPEN_REQUESTED", "BLOCKED_EXTERNAL", shift.businessDate(),
            shift.theoreticalCashMinor(), version);
        String payload = CanonicalJson.from(Map.<String, Object>ofEntries(
            Map.entry("drawerEventId", command.drawerEventId()), Map.entry("shiftId", shift.shiftId()),
            Map.entry("storeId", shift.storeId().toString()), Map.entry("terminalId", shift.terminalId()),
            Map.entry("cashierId", principal.userId().toString()),
            Map.entry("businessDate", shift.businessDate().toString()),
            Map.entry("eventType", "NO_SALE_OPEN_REQUESTED"),
            Map.entry("deviceExecutionStatus", "BLOCKED_EXTERNAL"),
            Map.entry("reasonCode", command.reasonCode()), Map.entry("reasonText", command.reasonText().trim()),
            Map.entry("expectedVersion", command.expectedVersion())
        )).json();
        journal.appendEvent(tenantId, "shift.event", "shift.drawer-requested.v1", "SHIFT",
            shift.shiftId(), version, command.commandId(), payload, at);
        journal.audit(tenantId, "NO_SALE_DRAWER_REQUESTED", "SHIFT", shift.shiftId(), principal.userId(),
            null, command.commandId(), "OPEN", "OPEN", null, requestHash, command.reasonCode(), at);
        idempotency.save(tenantId, DRAWER_REQUEST, command.commandId(), command.idempotencyKey(), requestHash,
            shift.shiftId(), result, at);
        return result;
    }

    @Transactional
    public ApprovalView approveDifference(ApproveDifference command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        OrderRules.requireUlid(command.commandId(), "commandId");
        OrderRules.requireUlid(command.shiftId(), "shiftId");
        OrderRules.requireIdempotencyKey(command.idempotencyKey());
        OrderRules.requireMoney(command.actualCashMinor(), "actualCashMinor");
        if (command.expectedVersion() <= 0 || command.occurredAt() == null
            || command.reasonCode() == null || !command.reasonCode().matches("^[A-Z][A-Z0-9_]{1,31}$")
            || command.reasonText() == null || command.reasonText().isBlank() || command.reasonText().length() > 256) {
            throw new ServiceException("SHIFT_APPROVAL_INPUT_INVALID: 审批计数、版本或原因不完整", 400);
        }
        ShiftView visible = requireShift(principal.tenantId(), command.shiftId());
        authorizationService.requireStoreAccess(visible.storeId());
        String requestHash = CanonicalHash.sha256(CanonicalHash.lengthPrefixed(
            List.of(command.shiftId(), command.actualCashMinor(), command.expectedVersion(),
                command.reasonCode(), command.reasonText(), principal.userId())));
        ApprovalView duplicate = idempotency.find(principal.tenantId(), APPROVE, command.idempotencyKey(), requestHash, ApprovalView.class);
        if (duplicate != null) {
            return duplicate;
        }
        ShiftView shift = mapper.lockShift(principal.tenantId(), command.shiftId());
        if (shift == null || !"OPEN".equals(shift.status()) || shift.recordVersion() != command.expectedVersion()) {
            throw new ServiceException("SHIFT_STATE_CONFLICT: 仅匹配版本的 OPEN 班次可审批差异", 409);
        }
        if (principal.userId().equals(shift.cashierUserId())) {
            throw new ServiceException("SHIFT_APPROVER_SEPARATION_REQUIRED: 审批人不得为本班收银员", 403);
        }
        long ledger = totalCashLedger(principal.tenantId(), shift.shiftId());
        long theoretical = safeAdd(shift.openingCashMinor(), ledger);
        long difference = safeSubtract(command.actualCashMinor(), theoretical);
        long threshold = differencePolicy.approvalThresholdMinor(shift.storeId());
        if (absoluteExceeds(difference, threshold) == false) {
            throw new ServiceException("SHIFT_APPROVAL_NOT_REQUIRED: 差异未超过配置阈值", 409);
        }
        String approvalId = ulids.next();
        LocalDateTime at = utc(command.occurredAt());
        mapper.insertApproval(principal.tenantId(), approvalId, shift.shiftId(), principal.userId(),
            command.reasonCode(), command.reasonText(), theoretical, command.actualCashMinor(), difference,
            command.expectedVersion(), at);
        ApprovalView result = mapper.findApproval(principal.tenantId(), shift.shiftId(), approvalId);
        String payload = CanonicalJson.from(Map.<String, Object>of("approvalId", approvalId,
            "shiftId", shift.shiftId(), "approverId", principal.userId().toString(),
            "reasonCode", command.reasonCode(), "theoreticalCashMinor", theoretical,
            "actualCashMinor", command.actualCashMinor(), "differenceMinor", difference)).json();
        journal.appendEvent(principal.tenantId(), "shift.event", "shift.difference-approved.v1", "SHIFT",
            shift.shiftId(), shift.recordVersion(), command.commandId(), payload, at);
        journal.audit(principal.tenantId(), "SHIFT_DIFFERENCE_APPROVED", "SHIFT", shift.shiftId(),
            principal.userId(), principal.userId(), command.commandId(), "OPEN", "OPEN", difference,
            requestHash, command.reasonCode(), at);
        idempotency.save(principal.tenantId(), APPROVE, command.commandId(), command.idempotencyKey(),
            requestHash, approvalId, result, at);
        return result;
    }

    @Transactional
    public ShiftView close(CloseShift command) {
        return closeInternal(new CloseInput(command.commandId(), command.idempotencyKey(), command.shiftId(),
            command.actualCashMinor(), command.expectedVersion(), command.approvalId(), command.occurredAt(), false));
    }

    /**
     * 同步关班保留 POS 冻结的版本身份，同时允许服务端已被成交、退款等权威事实推进。
     * 锁定行后的服务端版本只能等于或领先本地版本；真正关闭仍使用服务端当前版本，
     * 普通在线关班的严格等值乐观锁语义不受影响。
     */
    @Transactional
    @Override
    public ShiftView closeSynced(CloseSyncedShift command) {
        return closeInternal(new CloseInput(command.commandId(), command.idempotencyKey(), command.shiftId(),
            command.actualCashMinor(), command.localExpectedVersion(), command.approvalId(), command.occurredAt(),
            true));
    }

    private ShiftView closeInternal(CloseInput command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        OrderRules.requireUlid(command.commandId(), "commandId");
        OrderRules.requireUlid(command.shiftId(), "shiftId");
        OrderRules.requireIdempotencyKey(command.idempotencyKey());
        OrderRules.requireMoney(command.actualCashMinor(), "actualCashMinor");
        if (command.expectedVersion() <= 0) {
            throw new ServiceException("SHIFT_INPUT_INVALID: 关班版本必须为正数", 400);
        }
        String requestHash = CanonicalHash.sha256(CanonicalHash.lengthPrefixed(java.util.Arrays.asList(
            command.shiftId(), command.actualCashMinor(), command.expectedVersion(), command.approvalId())));
        ShiftView duplicate = idempotency.find(principal.tenantId(), CLOSE, command.idempotencyKey(), requestHash, ShiftView.class);
        if (duplicate != null) {
            return duplicate;
        }
        ShiftView shift = mapper.lockShift(principal.tenantId(), command.shiftId());
        if (shift == null) {
            throw new ServiceException("RESOURCE_NOT_VISIBLE: 班次不存在或不可见", 404);
        }
        authorizationService.requireStoreAccess(shift.storeId());
        if (!principal.userId().equals(shift.cashierUserId()) || !"OPEN".equals(shift.status())) {
            throw new ServiceException("SHIFT_STATE_CONFLICT: 班次状态、操作者或版本冲突", 409);
        }
        if (command.synchronizedClose()) {
            if (shift.recordVersion() < command.expectedVersion()) {
                throw new ServiceException("SHIFT_SYNC_VERSION_AHEAD: POS 关班版本领先服务端", 409);
            }
        } else if (command.expectedVersion() != shift.recordVersion()) {
            throw new ServiceException("SHIFT_STATE_CONFLICT: 班次状态、操作者或版本冲突", 409);
        }
        long authoritativeVersion = shift.recordVersion();
        long ledger = totalCashLedger(principal.tenantId(), shift.shiftId());
        long theoretical = safeAdd(shift.openingCashMinor(), ledger);
        long difference = safeSubtract(command.actualCashMinor(), theoretical);
        long threshold = differencePolicy.approvalThresholdMinor(shift.storeId());
        ApprovalView approval = null;
        if (absoluteExceeds(difference, threshold)) {
            if (command.approvalId() == null) {
                throw new ServiceException("SHIFT_DIFFERENCE_APPROVAL_REQUIRED: 交班差异超过阈值", 409);
            }
            approval = mapper.findApproval(principal.tenantId(), shift.shiftId(), command.approvalId());
            if (approval == null || approval.approverUserId().equals(shift.cashierUserId())
                || approval.theoreticalCashMinor() != theoretical
                || approval.actualCashMinor() != command.actualCashMinor()
                || approval.differenceMinor() != difference
                || approval.expectedShiftVersion() != command.expectedVersion()) {
                throw new ServiceException("SHIFT_DIFFERENCE_APPROVAL_REQUIRED: 独立审批无效", 409);
            }
        } else if (command.approvalId() != null) {
            throw new ServiceException("SHIFT_APPROVAL_NOT_REQUIRED: 阈值内不得附加差异审批", 409);
        }
        LocalDateTime at = utc(command.occurredAt());
        if (mapper.closeShift(principal.tenantId(), shift.shiftId(), theoretical, command.actualCashMinor(),
            difference, approval == null ? null : approval.approvalId(), at, authoritativeVersion) != 1) {
            throw new ServiceException("SHIFT_STATE_CONFLICT: 并发关闭冲突", 409);
        }
        ShiftView result = requireShift(principal.tenantId(), shift.shiftId());
        String payload = CanonicalJson.from(Map.<String, Object>of("shiftId", shift.shiftId(),
            "businessDate", shift.businessDate().toString(), "currency", "CNY",
            "theoreticalCashMinor", theoretical, "actualCashMinor", command.actualCashMinor(),
            "differenceMinor", difference)).json();
        journal.appendEvent(principal.tenantId(), "shift.event", "shift.closed.v1", "SHIFT",
            shift.shiftId(), result.recordVersion(), command.commandId(), payload, at);
        journal.audit(principal.tenantId(), "SHIFT_CLOSED", "SHIFT", shift.shiftId(), principal.userId(),
            approval == null ? null : approval.approverUserId(), command.commandId(), "OPEN", "CLOSED",
            difference, requestHash, "CLOSE_SHIFT", at);
        idempotency.save(principal.tenantId(), CLOSE, command.commandId(), command.idempotencyKey(),
            requestHash, shift.shiftId(), result, at);
        return result;
    }

    /** 统一在线/同步关班输入；同步标识只改变版本收敛规则，不改变资金和审批规则。 */
    private record CloseInput(String commandId, String idempotencyKey, String shiftId,
                              long actualCashMinor, long expectedVersion, String approvalId,
                              Instant occurredAt, boolean synchronizedClose) { }

    private ShiftView requireShift(String tenantId, String shiftId) {
        ShiftView result = mapper.findShift(tenantId, shiftId);
        if (result == null) {
            throw new ServiceException("RESOURCE_NOT_VISIBLE: 班次不存在或不可见", 404);
        }
        return result;
    }

    private ShiftView lockOwnedOpenShift(TrustedPrincipal principal, String shiftId, long expectedVersion) {
        ShiftView shift = mapper.lockShift(principal.tenantId(), shiftId);
        if (shift == null) {
            throw new ServiceException("RESOURCE_NOT_VISIBLE: 班次不存在或不可见", 404);
        }
        authorizationService.requireStoreAccess(shift.storeId());
        if (!principal.userId().equals(shift.cashierUserId()) || !"OPEN".equals(shift.status())
            || shift.recordVersion() != expectedVersion) {
            throw new ServiceException("SHIFT_STATE_CONFLICT: 班次状态、操作者或版本冲突", 409);
        }
        return shift;
    }

    private void validateOperationIdentity(String commandId, String idempotencyKey, String operationId,
                                           String shiftId, long expectedVersion, String reasonCode,
                                           String reasonText, String authorizationRef, Instant occurredAt) {
        OrderRules.requireUlid(commandId, "commandId");
        OrderRules.requireUlid(operationId, "operationId");
        OrderRules.requireUlid(shiftId, "shiftId");
        OrderRules.requireIdempotencyKey(idempotencyKey);
        if (expectedVersion <= 0 || occurredAt == null || reasonCode == null
            || !reasonCode.matches("^[A-Z][A-Z0-9_]{1,31}$") || reasonText == null
            || reasonText.isBlank() || reasonText.length() > 256 || authorizationRef == null
            || !authorizationRef.matches("^[A-Za-z0-9._:-]{16,128}$")) {
            throw new ServiceException("SHIFT_OPERATION_INPUT_INVALID: 班次操作上下文不完整", 400);
        }
    }

    private long totalCashLedger(String tenantId, String shiftId) {
        return safeAdd(mapper.sumCashLedger(tenantId, shiftId), mapper.sumNonSaleCashMovement(tenantId, shiftId));
    }

    private void requireActor(String cashierId, TrustedPrincipal principal) {
        if (cashierId == null || !cashierId.equals(principal.userId().toString())) {
            throw new ServiceException("PERMISSION_DENIED: cashierId 必须匹配可信操作者", 403);
        }
    }

    private String safeName(TrustedPrincipal principal) {
        return principal.username() == null || principal.username().isBlank() ? principal.userId().toString()
            : principal.username().substring(0, Math.min(64, principal.username().length()));
    }

    private LocalDateTime utc(java.time.Instant value) {
        return LocalDateTime.ofInstant(value == null ? clock.instant() : value, ZoneOffset.UTC);
    }

    private long safeAdd(long left, long right) {
        try {
            return OrderRules.requireMoney(Math.addExact(left, right), "theoreticalCashMinor");
        } catch (ArithmeticException exception) {
            throw new ServiceException("SHIFT_AMOUNT_OVERFLOW: 班次现金汇总溢出", 409);
        }
    }

    private long safeSubtract(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ServiceException("SHIFT_AMOUNT_OVERFLOW: 班次差异溢出", 409);
        }
    }

    private boolean absoluteExceeds(long value, long threshold) {
        return value == Long.MIN_VALUE || Math.abs(value) > threshold;
    }
}
