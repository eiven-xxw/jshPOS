package com.jingshanghui.pos.promotion.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.promotion.application.model.MemberBenefitPromotionModels.*;
import com.jingshanghui.pos.promotion.application.service.MemberBenefitPromotionService;
import com.jingshanghui.pos.promotion.domain.PromotionModels.BasketLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** T2-MEM-003 会员权益询价协议适配器；不接受 tenant_id，不在前端计算价格。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/promotions/member-benefit")
public class MemberBenefitPromotionController {
    private static final String ULID="^[0-9A-HJKMNP-TV-Z]{26}$";
    private final MemberBenefitPromotionService service;

    @PostMapping("/quotes")
    @SaCheckPermission("promotion:quote:calculate")
    public R<MemberQuoteView> quote(@Valid @RequestBody QuoteRequest request){
        List<MemberQuoteLine> lines=request.lines().stream().map(line->new MemberQuoteLine(new BasketLine(
            line.lineId(),line.lineNo(),Long.valueOf(line.skuId()),nullable(line.categoryId()),
            nullable(line.brandId()),new BigDecimal(line.quantity()),line.unitPriceMinor()),Long.valueOf(line.unitId()))).toList();
        return R.ok(service.quote(new MemberQuote(request.pricingRequestId(),Long.valueOf(request.storeId()),
            request.terminalId(),request.channel(),request.businessTime(),request.currency(),request.packageVersion(),
            request.entitlementSnapshotId(),lines,request.correlationId())));
    }

    /** REST 请求只携带业务引用；租户、能力开关和叠加许可均由服务端可信上下文决定。 */
    public record QuoteRequest(@NotBlank @Pattern(regexp=ULID) String pricingRequestId,
                               @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String storeId,
                               @NotBlank @Pattern(regexp=ULID) String terminalId,
                               @NotBlank @Size(max=32) String channel,@NotNull OffsetDateTime businessTime,
                               @NotBlank @Pattern(regexp="^CNY$") String currency,@Positive long packageVersion,
                               @Pattern(regexp=ULID) String entitlementSnapshotId,
                               @NotEmpty @Size(max=500) List<@Valid LineRequest> lines,
                               @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    /** 精确数量用字符串传输，避免 JSON 浮点数。 */
    public record LineRequest(@NotBlank @Pattern(regexp=ULID) String lineId,@Min(1) @Max(500) int lineNo,
                              @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String skuId,
                              @Pattern(regexp="^[1-9][0-9]{0,18}$") String categoryId,
                              @Pattern(regexp="^[1-9][0-9]{0,18}$") String brandId,
                              @NotBlank @Pattern(regexp="^[1-9][0-9]{0,18}$") String unitId,
                              @NotBlank @Pattern(regexp="^[0-9]+(?:\\.[0-9]{1,6})?$") String quantity,
                              @PositiveOrZero long unitPriceMinor) { }
    private Long nullable(String value){return value==null?null:Long.valueOf(value);}
}
