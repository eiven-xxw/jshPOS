package com.jingshanghui.pos.catalog.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageArtifact;
import com.jingshanghui.pos.catalog.application.service.CatalogPackageService;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.PublishPackage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Base64;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/catalog/packages")
public class CatalogPackageController {

    private final CatalogPackageService service;

    @PostMapping
    @SaCheckPermission("catalog:package:publish")
    @Log(title = "商品价格数据包", businessType = BusinessType.INSERT)
    public R<PackageView> publish(@Valid @RequestBody PublishPackage request) {
        return R.ok(service.publish(request.storeId(), request.packageVersion(), request.previousVersion()));
    }

    @GetMapping("/latest")
    @SaCheckPermission("catalog:package:query")
    public R<PackageView> latest(@RequestParam Long storeId) {
        return R.ok(service.latest(storeId));
    }

    /** 下载原始商品价格 canonical 包；签名与摘要只通过响应头传递。 */
    @GetMapping(value = "/{packageVersion}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @SaCheckPermission("catalog:package:query")
    public ResponseEntity<byte[]> content(@PathVariable long packageVersion,
                                          @RequestParam Long storeId) {
        PackageArtifact artifact = service.download(storeId, packageVersion);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-JSH-Payload-Sha256", artifact.payloadSha256())
            .header("X-JSH-Signing-Key-Id", artifact.signingKeyId())
            .header("X-JSH-Signature", Base64.getEncoder().encodeToString(artifact.signature()))
            .body(artifact.payload());
    }
}
