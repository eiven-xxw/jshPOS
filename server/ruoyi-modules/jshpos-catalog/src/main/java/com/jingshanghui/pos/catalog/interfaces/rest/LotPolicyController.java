package com.jingshanghui.pos.catalog.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PolicyView;
import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PublishCommand;
import com.jingshanghui.pos.catalog.application.service.LotPolicyService;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.LotPolicyRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 批次策略协议适配器；只做参数转换，业务规则留在 Catalog 应用/领域层。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/catalog/lot-policies")
public class LotPolicyController {
    private final LotPolicyService service;

    @PostMapping
    @SaCheckPermission("catalog:lot-policy:publish")
    @Log(title = "发布批次效期策略", businessType = BusinessType.INSERT)
    public R<PolicyView> publish(@Valid @RequestBody LotPolicyRequests.Publish request,
                                 @RequestHeader("X-Correlation-ID") String correlationId) {
        return R.ok(service.publish(new PublishCommand(request.policyVersionId(), request.storeId(), request.skuId(),
            request.enabled(), request.expiryBasis(), request.shelfLifeDays(), request.nearExpiryDays(),
            request.effectiveFrom(), correlationId)));
    }

    @GetMapping("/effective")
    @SaCheckPermission("catalog:lot-policy:read")
    public R<PolicyView> effective(@RequestParam @Positive Long storeId, @RequestParam @Positive Long skuId,
                                   @RequestParam(required = false) Instant effectiveAt) {
        return R.ok(service.requireEffective(storeId, skuId, effectiveAt));
    }
}
