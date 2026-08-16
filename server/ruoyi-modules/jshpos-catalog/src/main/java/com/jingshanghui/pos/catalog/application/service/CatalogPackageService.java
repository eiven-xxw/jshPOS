package com.jingshanghui.pos.catalog.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageView;
import com.jingshanghui.pos.catalog.application.packagev1.CatalogPackageCodec;
import com.jingshanghui.pos.catalog.application.packagev1.PackageObjectPort;
import com.jingshanghui.pos.catalog.application.packagev1.PackageSigningPort;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.security.TenantResourceNamespace;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CatalogPackageService {

    private final CatalogMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final TenantResourceNamespace namespace;
    private final DomainAuditService auditService;
    private final ObjectProvider<PackageSigningPort> signingPorts;
    private final ObjectProvider<PackageObjectPort> objectPorts;
    private final Clock clock;
    private final CatalogOutboxService outboxService;

    @Transactional
    public PackageView publish(Long storeId, long packageVersion, long previousVersion) {
        String tenantId = tenantContext.requireTenantId();
        authorizationService.requireStoreAccess(storeId);
        PackageSigningPort signingPort = signingPorts.getIfAvailable();
        PackageObjectPort objectPort = objectPorts.getIfAvailable();
        if (signingPort == null || objectPort == null) {
            throw new ServiceException("CAT-DPK-010: KMS/HSM 签名或对象存储端口未配置", 503);
        }
        PackageView latest = mapper.findLatestPackage(tenantId, storeId);
        long expectedPrevious = latest == null ? 0 : latest.packageVersion();
        long expectedVersion = expectedPrevious + 1;
        if (previousVersion != expectedPrevious || packageVersion != expectedVersion) {
            throw new ServiceException("CAT-DPK-012: 数据包版本必须严格连续且引用当前前版", 409);
        }
        Instant generatedAt = clock.instant();
        List<CatalogPackageCodec.Record> records = packageRecords(tenantId, storeId);
        CatalogPackageCodec.EncodedPackage encoded = CatalogPackageCodec.encode(
            tenantId, storeId, packageVersion, previousVersion, generatedAt, records);
        PackageSigningPort.SigningResult signed = signingPort.sign(tenantId, encoded.payload());
        String resourceName = "catalog-" + storeId + "-" + packageVersion + "-" + encoded.sha256() + ".jshpkg";
        String objectKey = namespace.objectKey(resourceName);
        objectPort.put(objectKey, encoded.payload(), signed.signature());
        Long packageId = IdWorker.getId();
        mapper.insertPackage(tenantId, packageId, storeId, packageVersion, previousVersion,
            CatalogPackageCodec.SCHEMA_VERSION, encoded.sha256(), signed.keyId(), objectKey,
            encoded.recordCount(), LocalDateTime.ofInstant(generatedAt, ZoneOffset.UTC));
        PackageView result = mapper.findLatestPackage(tenantId, storeId);
        auditService.append("CATALOG_PACKAGE_PUBLISHED", "CATALOG_PACKAGE", packageId, null, result,
            Map.of("storeId", storeId, "version", packageVersion, "sha256", encoded.sha256()));
        outboxService.append(tenantId, "data-package.available.v1", "CATALOG_PACKAGE", packageId, packageVersion,
            "{\"packageId\":" + packageId + ",\"storeId\":" + storeId +
                ",\"packageVersion\":" + packageVersion + ",\"payloadSha256\":\"" + encoded.sha256() + "\"}");
        return result;
    }

    @Transactional(readOnly = true)
    public PackageView latest(Long storeId) {
        String tenantId = tenantContext.requireTenantId();
        authorizationService.requireStoreAccess(storeId);
        PackageView result = mapper.findLatestPackage(tenantId, storeId);
        if (result == null) {
            throw new ServiceException("CAT-DPK-011: 数据包不存在或不可见", 404);
        }
        return result;
    }

    private List<CatalogPackageCodec.Record> packageRecords(String tenantId, Long storeId) {
        List<CatalogPackageCodec.Record> result = new ArrayList<>();
        int index = 0;
        for (String row : mapper.listProductPackageRows(tenantId)) {
            result.add(new CatalogPackageCodec.Record("PRODUCT", String.format("%09d", index++), row));
        }
        index = 0;
        for (String row : mapper.listPricePackageRows(tenantId, storeId)) {
            result.add(new CatalogPackageCodec.Record("PRICE", String.format("%09d", index++), row));
        }
        return result;
    }
}
