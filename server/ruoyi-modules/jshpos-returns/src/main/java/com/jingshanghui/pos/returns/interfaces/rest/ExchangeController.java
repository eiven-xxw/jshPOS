package com.jingshanghui.pos.returns.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.returns.application.model.ExchangeCommands.ApproveExchange;
import com.jingshanghui.pos.returns.application.model.ExchangeCommands.CreateExchange;
import com.jingshanghui.pos.returns.application.model.ExchangeCommands.RecoverExchange;
import com.jingshanghui.pos.returns.application.model.ExchangeViews.ExchangeView;
import com.jingshanghui.pos.returns.application.service.ExchangeOrchestrationService;
import com.jingshanghui.pos.returns.application.service.ExchangeSagaCoordinator;
import com.jingshanghui.pos.returns.interfaces.rest.dto.ExchangeRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 换货关联 API；Owner 观察和 Saga 推进只允许内部执行器调用应用服务。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/pos/exchanges")
public class ExchangeController {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final ExchangeOrchestrationService service;
    private final ExchangeSagaCoordinator coordinator;

    @PostMapping
    @SaCheckPermission("pos:exchange:create")
    @Log(title = "创建基础换货关联", businessType = BusinessType.INSERT)
    public R<ExchangeView> create(@Valid @RequestBody ExchangeRequests.Create request) {
        return R.ok(service.create(new CreateExchange(request.commandId(), request.idempotencyKey(),
            request.exchangeId(), request.returnId(), request.originalOrderId(),
            request.originalReturnCommandId(), request.newOrderId(), request.newSaleCommandId(),
            request.storeId(), request.terminalId(), request.businessDate(),
            request.expectedRefundAmountMinor(), request.expectedSaleReceivableMinor(),
            request.quoteFingerprint(), request.newSalePlanSha256(), request.reasonCode(),
            request.correlationId(), request.occurredAt())));
    }

    @PostMapping("/{exchangeId}/approve")
    @SaCheckPermission("pos:exchange:approve")
    @Log(title = "审批基础换货关联", businessType = BusinessType.UPDATE)
    public R<ExchangeView> approve(@PathVariable @Pattern(regexp = ULID) String exchangeId,
                                   @Valid @RequestBody ExchangeRequests.Approve request) {
        return R.ok(service.approve(new ApproveExchange(request.commandId(), exchangeId,
            request.reasonCode(), request.correlationId(), request.occurredAt())));
    }

    @PostMapping("/{exchangeId}/recover")
    @SaCheckPermission("pos:exchange:recover")
    @Log(title = "恢复基础换货检查点", businessType = BusinessType.UPDATE)
    public R<ExchangeView> recover(@PathVariable @Pattern(regexp = ULID) String exchangeId,
                                   @Valid @RequestBody ExchangeRequests.Recover request) {
        return R.ok(service.recover(new RecoverExchange(request.commandId(), exchangeId,
            request.targetLeg(), request.reasonCode(), request.correlationId(), request.occurredAt())));
    }

    @GetMapping("/{exchangeId}")
    @SaCheckPermission("pos:exchange:read")
    public R<ExchangeView> detail(@PathVariable @Pattern(regexp = ULID) String exchangeId) {
        return R.ok(service.find(exchangeId));
    }

    @PostMapping("/{exchangeId}/observe")
    @SaCheckPermission("pos:exchange:read")
    @Log(title = "观察基础换货原命令", businessType = BusinessType.OTHER)
    public R<ExchangeView> observe(@PathVariable @Pattern(regexp = ULID) String exchangeId) {
        return R.ok(coordinator.processNext(exchangeId));
    }
}
