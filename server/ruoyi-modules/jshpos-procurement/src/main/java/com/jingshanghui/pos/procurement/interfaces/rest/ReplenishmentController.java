package com.jingshanghui.pos.procurement.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.procurement.application.model.ReplenishmentModels.*;
import com.jingshanghui.pos.procurement.application.service.ReplenishmentService;
import com.jingshanghui.pos.procurement.interfaces.rest.dto.ReplenishmentRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 补货规则、建议与采购草稿 REST 边界；所有计算结果均来自服务端。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/replenishment")
public class ReplenishmentController {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final ReplenishmentService service;

    @PostMapping("/policies")
    @SaCheckPermission("procurement:replenishment:policy")
    @Log(title = "创建补货规则", businessType = BusinessType.INSERT)
    public R<PolicyDetail> createPolicy(@Valid @RequestBody ReplenishmentRequests.PolicyCreate request) {
        return R.ok(service.createPolicy(new CreatePolicy(request.policyVersionId(), positive(request.storeId(), "storeId"),
            request.warehouseId(), request.versionNo(), request.effectiveFrom(), request.items().stream().map(item ->
            new PolicyItemInput(item.policyItemId(), positive(item.skuId(), "skuId"),
                positive(item.purchaseUnitId(), "purchaseUnitId"), item.supplierId(), item.minimumBaseQuantity(),
                item.maximumBaseQuantity(), item.minimumOrderQuantity(), item.orderMultiple(),
                item.includeConfirmedInTransit(), nonNegative(item.unitPriceMinor(), "unitPriceMinor"),
                item.taxRateBps())).toList(), request.idempotencyKey(), request.correlationId())));
    }

    @PostMapping("/policies/{policyVersionId}/publish")
    @SaCheckPermission("procurement:replenishment:policy")
    @Log(title = "发布补货规则", businessType = BusinessType.UPDATE)
    public R<PolicyDetail> publish(@PathVariable @Pattern(regexp = ULID) String policyVersionId,
                                   @Valid @RequestBody ReplenishmentRequests.StateCommand request) {
        return R.ok(service.publishPolicy(policyCommand(policyVersionId, request)));
    }

    @PostMapping("/policies/{policyVersionId}/retire")
    @SaCheckPermission("procurement:replenishment:policy")
    @Log(title = "停用补货规则", businessType = BusinessType.UPDATE)
    public R<PolicyDetail> retire(@PathVariable @Pattern(regexp = ULID) String policyVersionId,
                                  @Valid @RequestBody ReplenishmentRequests.StateCommand request) {
        return R.ok(service.retirePolicy(policyCommand(policyVersionId, request)));
    }

    @GetMapping("/policies")
    @SaCheckPermission("procurement:replenishment:read")
    public R<List<PolicyView>> policies(@RequestParam String storeId,
                                        @RequestParam(required = false) String state,
                                        @RequestParam(defaultValue = "100") int limit) {
        return R.ok(service.listPolicies(positive(storeId, "storeId"), state, limit));
    }

    @GetMapping("/policies/{policyVersionId}")
    @SaCheckPermission("procurement:replenishment:read")
    public R<PolicyDetail> policy(@PathVariable @Pattern(regexp = ULID) String policyVersionId) {
        return R.ok(service.policyDetail(policyVersionId));
    }

    @PostMapping("/suggestions/generate")
    @SaCheckPermission("procurement:replenishment:generate")
    @Log(title = "生成补货建议", businessType = BusinessType.INSERT)
    public R<GenerationResult> generate(@Valid @RequestBody ReplenishmentRequests.Generate request) {
        return R.ok(service.generate(new GenerateSuggestions(request.generationRunId(), request.policyVersionId(),
            request.calculationAt(), request.idempotencyKey(), request.correlationId())));
    }

    @GetMapping("/suggestions")
    @SaCheckPermission("procurement:replenishment:read")
    public R<List<SuggestionView>> suggestions(@RequestParam String storeId,
                                               @RequestParam(required = false) String state,
                                               @RequestParam(defaultValue = "100") int limit) {
        return R.ok(service.listSuggestions(positive(storeId, "storeId"), state, limit));
    }

    @PostMapping("/suggestions/{suggestionId}/review")
    @SaCheckPermission("procurement:replenishment:review")
    @Log(title = "复核补货建议", businessType = BusinessType.UPDATE)
    public R<SuggestionView> review(@PathVariable @Pattern(regexp = ULID) String suggestionId,
                                    @Valid @RequestBody ReplenishmentRequests.StateCommand request) {
        return R.ok(service.review(suggestionCommand(suggestionId, request)));
    }

    @PostMapping("/suggestions/{suggestionId}/approve")
    @SaCheckPermission("procurement:replenishment:approve")
    @Log(title = "审批补货建议", businessType = BusinessType.UPDATE)
    public R<SuggestionView> approve(@PathVariable @Pattern(regexp = ULID) String suggestionId,
                                     @Valid @RequestBody ReplenishmentRequests.StateCommand request) {
        return R.ok(service.approve(suggestionCommand(suggestionId, request)));
    }

    @PostMapping("/suggestions/{suggestionId}/reject")
    @SaCheckPermission("procurement:replenishment:review")
    @Log(title = "驳回补货建议", businessType = BusinessType.UPDATE)
    public R<SuggestionView> reject(@PathVariable @Pattern(regexp = ULID) String suggestionId,
                                    @Valid @RequestBody ReplenishmentRequests.StateCommand request) {
        return R.ok(service.reject(suggestionCommand(suggestionId, request)));
    }

    @PostMapping("/suggestions/{suggestionId}/purchase-draft")
    @SaCheckPermission("procurement:replenishment:draft")
    @Log(title = "补货建议转采购草稿", businessType = BusinessType.INSERT)
    public R<SuggestionView> draft(@PathVariable @Pattern(regexp = ULID) String suggestionId,
                                   @Valid @RequestBody ReplenishmentRequests.DraftCreate request) {
        return R.ok(service.createPurchaseDraft(new CreatePurchaseDraft(suggestionId, request.expectedVersion(),
            request.purchaseOrderId(), request.expectedDate(), request.idempotencyKey(), request.correlationId())));
    }

    private PolicyCommand policyCommand(String id, ReplenishmentRequests.StateCommand request) {
        return new PolicyCommand(id, request.expectedVersion(), request.idempotencyKey(), request.reason(),
            request.correlationId());
    }

    private SuggestionCommand suggestionCommand(String id, ReplenishmentRequests.StateCommand request) {
        return new SuggestionCommand(id, request.expectedVersion(), request.idempotencyKey(), request.reason(),
            request.correlationId());
    }

    private Long positive(String value, String field) {
        long number = parse(value, field);
        if (number <= 0) throw new ServiceException("RPL-INPUT-010: " + field + " 必须为正数", 400);
        return number;
    }

    private long nonNegative(String value, String field) {
        long number = parse(value, field);
        if (number < 0) throw new ServiceException("RPL-INPUT-011: " + field + " 不可为负", 400);
        return number;
    }

    private long parse(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new ServiceException("RPL-INPUT-012: " + field + " 超出 BIGINT 范围", 400);
        }
    }
}
