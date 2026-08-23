package com.jingshanghui.pos.saas.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.saas.application.model.SaasModels.EntitlementDecision;
import com.jingshanghui.pos.saas.application.service.SaasEntitlementService;
import com.jingshanghui.pos.saas.interfaces.rest.dto.SaasRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 当前可信租户的服务端权益决策边界。 */
@Validated @RestController @RequiredArgsConstructor @RequestMapping("/api/v1/saas/entitlement-decisions")
public class SaasEntitlementController {
    private final SaasEntitlementService service;
    @GetMapping("/{featureCode}") @SaCheckPermission("saas:entitlement:read")
    public R<EntitlementDecision> decide(@PathVariable @Pattern(regexp="^[A-Za-z][A-Za-z0-9_]{1,63}$") String featureCode){return R.ok(service.decide(featureCode));}
    @PostMapping("/{featureCode}/consume") @SaCheckPermission("saas:entitlement:consume")
    public R<EntitlementDecision> consume(@PathVariable @Pattern(regexp="^[A-Za-z][A-Za-z0-9_]{1,63}$") String featureCode,@Valid @RequestBody SaasRequests.QuotaConsume request){return R.ok(service.consume(featureCode,request.delta()));}
}
