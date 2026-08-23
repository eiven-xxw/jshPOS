package com.jingshanghui.pos.operations.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.operations.application.model.DailyCloseModels.*;
import com.jingshanghui.pos.operations.application.service.DailyCloseService;
import com.jingshanghui.pos.operations.interfaces.rest.dto.DailyCloseRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 门店业务日日结 REST 边界；Controller 不计算任何业务金额或状态。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/operations/daily-closes")
public class DailyCloseController {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String SAFE = "^[A-Za-z0-9._:-]+$";
    private final DailyCloseService service;

    @PostMapping
    @SaCheckPermission("operations:daily-close:create")
    @Log(title = "创建门店日结", businessType = BusinessType.INSERT)
    public R<CloseDetail> create(@RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                                 @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation,
                                 @Valid @RequestBody DailyCloseRequests.Create request) {
        return R.ok(service.create(new CreateClose(request.storeId(), request.businessDate(),
            request.correctionOfCloseId(), request.correctionReason(), key, correlation)));
    }

    @GetMapping
    @SaCheckPermission("operations:daily-close:read")
    public R<List<CloseRecord>> list(@RequestParam Long storeId,
                                     @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate businessDate,
                                     @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return R.ok(service.list(storeId, businessDate, limit));
    }

    @GetMapping("/{closeId}")
    @SaCheckPermission("operations:daily-close:read")
    public R<CloseDetail> detail(@PathVariable @Pattern(regexp=ULID) String closeId) {
        return R.ok(service.detail(closeId));
    }

    @PostMapping("/{closeId}/preflight")
    @SaCheckPermission("operations:daily-close:preflight")
    @Log(title = "门店日结预检", businessType = BusinessType.UPDATE)
    public R<CloseDetail> preflight(@PathVariable @Pattern(regexp=ULID) String closeId,
                                    @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                                    @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.preflight(new CloseCommand(closeId,key,correlation)));
    }

    @PostMapping("/{closeId}/approve")
    @SaCheckPermission("operations:daily-close:approve")
    @Log(title = "审批门店日结", businessType = BusinessType.UPDATE)
    public R<CloseDetail> approve(@PathVariable @Pattern(regexp=ULID) String closeId,
                                  @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                                  @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation,
                                  @Valid @RequestBody DailyCloseRequests.Reason request) {
        return R.ok(service.approve(new ApprovalCommand(closeId,request.reason(),key,correlation)));
    }

    @PostMapping("/{closeId}/close")
    @SaCheckPermission("operations:daily-close:sign")
    @Log(title = "签署门店日结", businessType = BusinessType.UPDATE)
    public R<CloseDetail> close(@PathVariable @Pattern(regexp=ULID) String closeId,
                                @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                                @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.signAndClose(new CloseCommand(closeId,key,correlation)));
    }

    @PostMapping("/{closeId}/late-facts")
    @SaCheckPermission("operations:daily-close:late-fact")
    @Log(title = "扫描日结晚到事实", businessType = BusinessType.UPDATE)
    public R<CloseDetail> lateFacts(@PathVariable @Pattern(regexp=ULID) String closeId,
                                    @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                                    @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.detectLateFacts(new CloseCommand(closeId,key,correlation)));
    }
}
