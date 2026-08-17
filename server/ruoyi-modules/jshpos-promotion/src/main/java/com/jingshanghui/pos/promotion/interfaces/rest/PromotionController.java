package com.jingshanghui.pos.promotion.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.*;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.*;
import com.jingshanghui.pos.promotion.application.service.PromotionService;
import com.jingshanghui.pos.promotion.application.service.PromotionPackageService;
import com.jingshanghui.pos.promotion.application.service.ManualPromotionService;
import com.jingshanghui.pos.promotion.application.service.PromotionTransactionService;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine.ActionType;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine.PaymentMethod;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import com.jingshanghui.pos.promotion.interfaces.rest.dto.PromotionRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Base64;

/** Gate 5A 促销 API；Controller 只做校验和 DTO 转换。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/promotions")
public class PromotionController {
    private final PromotionService service;
    private final PromotionPackageService packages;
    private final ManualPromotionService manualPromotions;
    private final PromotionTransactionService transactions;

    /** 创建规则身份和首版草稿。 */
    @PostMapping("/rules")
    @SaCheckPermission("promotion:rule:create")
    @Log(title="创建促销规则", businessType=BusinessType.INSERT)
    public R<RuleVersionView> create(@Valid @RequestBody PromotionRequests.Create request) {
        RuleVersion definition = definition(request.ruleVersionId(), request.definition());
        return R.ok(service.create(new CreateRule(request.commandId(), request.ruleId(), request.ruleVersionId(),
            request.ruleCode(), request.name(), definition, request.correlationId())));
    }

    /** 静态预检规则版本。 */
    @PostMapping("/rules/{ruleId}/versions/{versionId}/validate")
    @SaCheckPermission("promotion:rule:validate")
    public R<RuleVersionView> validate(@PathVariable @Pattern(regexp=PromotionRequests.ULID) String ruleId,
                                       @PathVariable @Pattern(regexp=PromotionRequests.ULID) String versionId,
                                       @Valid @RequestBody PromotionRequests.State request) {
        return R.ok(service.validate(state(ruleId, versionId, request)));
    }

    /** 审批规则版本。 */
    @PostMapping("/rules/{ruleId}/versions/{versionId}/approve")
    @SaCheckPermission("promotion:rule:approve")
    @Log(title="审批促销规则", businessType=BusinessType.UPDATE)
    public R<RuleVersionView> approve(@PathVariable @Pattern(regexp=PromotionRequests.ULID) String ruleId,
                                      @PathVariable @Pattern(regexp=PromotionRequests.ULID) String versionId,
                                      @Valid @RequestBody PromotionRequests.State request) {
        return R.ok(service.approve(state(ruleId, versionId, request)));
    }

    /** 发布规则版本。 */
    @PostMapping("/rules/{ruleId}/versions/{versionId}/publish")
    @SaCheckPermission("promotion:rule:publish")
    @Log(title="发布促销规则", businessType=BusinessType.UPDATE)
    public R<RuleVersionView> publish(@PathVariable @Pattern(regexp=PromotionRequests.ULID) String ruleId,
                                      @PathVariable @Pattern(regexp=PromotionRequests.ULID) String versionId,
                                      @Valid @RequestBody PromotionRequests.State request) {
        return R.ok(service.publish(state(ruleId, versionId, request)));
    }

    /** 暂停新询价使用规则版本。 */
    @PostMapping("/rules/{ruleId}/versions/{versionId}/pause")
    @SaCheckPermission("promotion:rule:publish")
    @Log(title="暂停促销规则", businessType=BusinessType.UPDATE)
    public R<RuleVersionView> pause(@PathVariable @Pattern(regexp=PromotionRequests.ULID) String ruleId,
                                    @PathVariable @Pattern(regexp=PromotionRequests.ULID) String versionId,
                                    @Valid @RequestBody PromotionRequests.State request) {
        return R.ok(service.pause(state(ruleId, versionId, request)));
    }

    /** 驳回尚未发布的规则版本。 */
    @PostMapping("/rules/{ruleId}/versions/{versionId}/reject")
    @SaCheckPermission("promotion:rule:approve")
    @Log(title="驳回促销规则", businessType=BusinessType.UPDATE)
    public R<RuleVersionView> reject(@PathVariable @Pattern(regexp=PromotionRequests.ULID) String ruleId,
                                     @PathVariable @Pattern(regexp=PromotionRequests.ULID) String versionId,
                                     @Valid @RequestBody PromotionRequests.State request) {
        return R.ok(service.reject(state(ruleId, versionId, request)));
    }

    /** 退役已发布或已暂停的规则版本。 */
    @PostMapping("/rules/{ruleId}/versions/{versionId}/retire")
    @SaCheckPermission("promotion:rule:publish")
    @Log(title="退役促销规则", businessType=BusinessType.UPDATE)
    public R<RuleVersionView> retire(@PathVariable @Pattern(regexp=PromotionRequests.ULID) String ruleId,
                                     @PathVariable @Pattern(regexp=PromotionRequests.ULID) String versionId,
                                     @Valid @RequestBody PromotionRequests.State request) {
        return R.ok(service.retire(state(ruleId, versionId, request)));
    }

    /** 执行确定性促销询价。 */
    @PostMapping("/quotes")
    @SaCheckPermission("promotion:quote:calculate")
    public R<QuoteView> quote(@Valid @RequestBody PromotionRequests.Quote request) {
        List<BasketLine> lines = request.lines().stream().map(line -> new BasketLine(line.lineId(), line.lineNo(),
            positive(line.skuId()), nullable(line.categoryId()), nullable(line.brandId()),
            new BigDecimal(line.quantity()), line.unitPriceMinor())).toList();
        return R.ok(service.quote(new Quote(request.pricingRequestId(), positive(request.storeId()),
            request.terminalId(), request.channel(), request.businessTime(), request.currency(),
            request.packageVersion(), lines, request.correlationId())));
    }

    /** 构建、签名并发布门店绑定的离线促销规则包。 */
    @PostMapping("/packages")
    @SaCheckPermission("promotion:rule:publish")
    @Log(title="发布促销规则包", businessType=BusinessType.INSERT)
    public R<PackageView> publishPackage(@Valid @RequestBody PromotionRequests.PublishPackage request) {
        return R.ok(packages.publish(positive(request.storeId()), request.packageVersion(),
            request.previousVersion(), request.expiresAt(), request.correlationId()));
    }

    /** 查询门店绑定的不可变促销规则包。 */
    @GetMapping("/packages/{packageVersion}")
    @SaCheckPermission("promotion:package:read")
    public R<PackageView> packageDetail(@PathVariable @Positive long packageVersion,
                                        @RequestParam @Pattern(regexp="^[1-9][0-9]{0,18}$") String storeId) {
        return R.ok(packages.require(positive(storeId), packageVersion));
    }

    /** 下载原始 canonical 规则包；签名和摘要通过响应头传递。 */
    @GetMapping(value="/packages/{packageVersion}/content", produces=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @SaCheckPermission("promotion:package:read")
    public ResponseEntity<byte[]> packageContent(@PathVariable @Positive long packageVersion,
                                                  @RequestParam @Pattern(regexp="^[1-9][0-9]{0,18}$") String storeId) {
        PackageArtifact artifact = packages.download(positive(storeId), packageVersion);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-JSH-Payload-Sha256", artifact.payloadSha256())
            .header("X-JSH-Signing-Key-Id", artifact.signingKeyId())
            .header("X-JSH-Signature", Base64.getEncoder().encodeToString(artifact.signature()))
            .body(artifact.payload());
    }

    /** 执行阈值内人工优惠或创建待独立复核申请。 */
    @PostMapping("/manual-authorizations")
    @SaCheckPermission("promotion:manual:authorize")
    @Log(title="人工优惠授权", businessType=BusinessType.INSERT)
    public R<ManualAuthorizationView> authorizeManual(
        @Valid @RequestBody PromotionRequests.ManualAuthorize request) {
        return R.ok(manualPromotions.authorize(new ManualAuthorize(request.commandId(), request.authorizationId(),
            request.quoteId(), enumValue(ActionType.class, request.actionType(), "人工优惠动作"), request.lineId(),
            request.amountOrRate(), enumValue(PaymentMethod.class, request.paymentMethod(), "支付方式"),
            request.expectedQuoteFingerprint(), request.reasonCode(), request.reasonText(), request.correlationId())));
    }

    /** 由不同的当前认证主体批准超阈值人工优惠。 */
    @PostMapping("/manual-authorizations/{authorizationId}/approve")
    @SaCheckPermission("promotion:manual:approve")
    @Log(title="复核人工优惠", businessType=BusinessType.UPDATE)
    public R<ManualAuthorizationView> approveManual(
        @PathVariable @Pattern(regexp=PromotionRequests.ULID) String authorizationId,
        @Valid @RequestBody PromotionRequests.ManualApprove request) {
        return R.ok(manualPromotions.approve(new ManualApprove(request.commandId(), authorizationId,
            request.expectedPreviewFingerprint(), request.reason(), request.correlationId())));
    }

    /** 为订单冻结不可变成交优惠快照与逐行来源分摊。 */
    @PostMapping("/snapshots")
    @SaCheckPermission("promotion:snapshot:freeze")
    @Log(title="冻结成交优惠快照", businessType=BusinessType.INSERT)
    public R<SnapshotView> freezeSnapshot(@Valid @RequestBody PromotionRequests.FreezeSnapshot request) {
        return R.ok(transactions.freeze(new FreezeSnapshot(request.commandId(), request.snapshotId(),
            request.orderId(), request.quoteId(), request.quoteFingerprint(), request.correlationId())));
    }

    /** 按原成交快照和累计上限追加本次退款优惠恢复流水。 */
    @PostMapping("/snapshots/{snapshotId}/refund-allocations")
    @SaCheckPermission("promotion:refund:allocate")
    @Log(title="计算退款优惠恢复", businessType=BusinessType.INSERT)
    public R<RefundAllocationView> allocateRefund(
        @PathVariable @Pattern(regexp=PromotionRequests.ULID) String snapshotId,
        @Valid @RequestBody PromotionRequests.AllocateRefund request) {
        List<com.jingshanghui.pos.promotion.application.model.PromotionCommands.RefundLine> lines =
            request.lines().stream().map(line ->
                new com.jingshanghui.pos.promotion.application.model.PromotionCommands.RefundLine(
                    line.lineId(), new BigDecimal(line.quantity()))).toList();
        return R.ok(transactions.allocateRefund(new AllocateRefund(request.commandId(), snapshotId,
            request.refundId(), lines, request.correlationId())));
    }

    private StateCommand state(String ruleId, String versionId, PromotionRequests.State request) {
        return new StateCommand(request.commandId(), ruleId, versionId, request.expectedVersion(),
            request.reason(), request.correlationId());
    }

    private RuleVersion definition(String versionId, PromotionRequests.Definition value) {
        PromotionRequests.Scope scope = value.scope(); PromotionRequests.Benefit benefit = value.benefit();
        List<BundleComponent> components = safe(benefit.bundleComponents()).stream().map(item ->
            new BundleComponent(positive(item.skuId()), new BigDecimal(item.quantity()))).toList();
        return new RuleVersion(versionId, enumValue(RuleType.class, value.ruleType(), "规则类型"), value.priority(),
            enumValue(StackMode.class, value.stackMode(), "叠加模式"), value.exclusiveGroup(), value.effectiveFrom(),
            value.effectiveTo(), new RuleScope(longs(scope.skuIds()), longs(scope.categoryIds()),
            longs(scope.brandIds()), longs(scope.storeIds()), new LinkedHashSet<>(safe(scope.channels())),
            new LinkedHashSet<>(safe(scope.businessDays()))),
            new RuleBenefit(benefit.amountMinor(), decimal(benefit.discountRate()), benefit.nth(),
                benefit.thresholdMinor(), decimal(benefit.thresholdQuantity()), benefit.bundlePriceMinor(), components));
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try { return Enum.valueOf(type, value); }
        catch (RuntimeException exception) { throw new ServiceException("PRM-INPUT-002: " + field + "无效", 400); }
    }
    private Set<Long> longs(List<String> values) { Set<Long> result=new LinkedHashSet<>(); safe(values).forEach(value -> result.add(positive(value))); return result; }
    private Long positive(String value) { try { long parsed=Long.parseLong(value); if(parsed<=0) throw new NumberFormatException(); return parsed; } catch(NumberFormatException exception) { throw new ServiceException("PRM-INPUT-003: BIGINT标识无效",400); } }
    private Long nullable(String value) { return value == null ? null : positive(value); }
    private BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
    private <T> List<T> safe(List<T> value) { return value == null ? List.of() : value; }
}
