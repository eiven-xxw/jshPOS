package com.jingshanghui.pos.catalog.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.*;
import com.jingshanghui.pos.catalog.application.service.MemberPriceService;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.MemberPriceRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** T2-MEM-003 Pricing Owner API；价格状态和摘要均由服务端决定。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/member-price-versions")
public class MemberPriceController {
    private final MemberPriceService service;

    @PostMapping
    @SaCheckPermission("pricing:member-price:publish")
    @Log(title="创建会员价版本",businessType=BusinessType.INSERT,isSaveRequestData=false)
    public R<VersionView> create(@Valid @RequestBody MemberPriceRequests.Create r){
        return R.ok(service.create(new CreateVersion(r.commandId(),r.versionId(),r.bookCode(),r.versionNo(),r.storeId(),
            r.items().stream().map(i->new ItemDraft(i.itemId(),i.levelCode(),i.skuId(),i.unitId(),i.amountMinor())).toList(),
            r.correlationId())));
    }
    @PostMapping("/{versionId}/validate")
    @SaCheckPermission("pricing:member-price:publish")
    public R<VersionView> validate(@PathVariable @Pattern(regexp=MemberPriceRequests.ULID) String versionId,
                                   @Valid @RequestBody MemberPriceRequests.Action r){return R.ok(service.validate(action(versionId,r)));}
    @PostMapping("/{versionId}/approve")
    @SaCheckPermission("pricing:member-price:publish")
    @Log(title="批准会员价版本",businessType=BusinessType.UPDATE,isSaveRequestData=false)
    public R<VersionView> approve(@PathVariable @Pattern(regexp=MemberPriceRequests.ULID) String versionId,
                                  @Valid @RequestBody MemberPriceRequests.Action r){return R.ok(service.approve(action(versionId,r)));}
    @PostMapping("/{versionId}/publish")
    @SaCheckPermission("pricing:member-price:publish")
    @Log(title="发布会员价版本",businessType=BusinessType.UPDATE,isSaveRequestData=false)
    public R<VersionView> publish(@PathVariable @Pattern(regexp=MemberPriceRequests.ULID) String versionId,
                                  @Valid @RequestBody MemberPriceRequests.Action r){return R.ok(service.publish(action(versionId,r)));}
    private VersionAction action(String versionId,MemberPriceRequests.Action r){return new VersionAction(r.commandId(),
        versionId,r.contentSha256(),r.effectiveAt(),r.expiresAt(),r.correlationId());}
}
