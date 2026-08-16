package com.jingshanghui.pos.payment.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RunReconciliation;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.StatementEntry;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.TransitionCase;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ReconciliationResult;
import com.jingshanghui.pos.payment.application.service.ReconciliationService;
import com.jingshanghui.pos.payment.interfaces.rest.dto.PaymentRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 受控账单双源对账 API；不负责从 Provider 下载账单。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final ReconciliationService service;

    @PostMapping("/runs")
    @SaCheckPermission("reconciliation:run")
    @Log(title = "执行支付对账", businessType = BusinessType.INSERT)
    public R<ReconciliationResult> run(@RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
                                       @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                       @Valid @RequestBody PaymentRequests.RunReconciliation request) {
        List<StatementEntry> entries = request.entries().stream().map(entry -> new StatementEntry(entry.entryId(),
            entry.providerTransactionNo(), entry.businessType(), entry.status(), entry.amountMinor(),
            entry.currency(), entry.occurredAt(), entry.payloadHash())).toList();
        return R.ok(service.run(new RunReconciliation(commandId, idempotencyKey, request.runId(),
            request.providerCode(), request.statementDate(), entries, request.occurredAt())));
    }

    @PostMapping("/cases/{caseId}/transition")
    @SaCheckPermission("reconciliation:manage")
    @Log(title = "处理对账差异", businessType = BusinessType.UPDATE)
    public R<Void> transition(@PathVariable @Pattern(regexp = ULID) String caseId,
                              @RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
                              @Valid @RequestBody PaymentRequests.TransitionCase request) {
        service.transition(new TransitionCase(commandId, caseId, request.targetStatus(), request.reasonCode(),
            request.reasonText(), request.occurredAt()));
        return R.ok();
    }
}
