package com.jingshanghui.pos.inventory.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageArtifact;
import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PolicyView;
import com.jingshanghui.pos.catalog.application.packagev1.CatalogPackageCodec;
import com.jingshanghui.pos.catalog.application.packagev1.PackageSigningPort;
import com.jingshanghui.pos.catalog.application.port.LotPolicyReadPort;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LotView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LotPackageRelease;
import com.jingshanghui.pos.inventory.domain.InventoryHash;
import com.jingshanghui.pos.inventory.domain.InventoryRules;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.LotInventoryMapper;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.AuditWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.LotPackageWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成并封存独立单调版本的批次数据包。
 *
 * <p>签名只能来自正式 KMS/HSM 端口；未配置时失败关闭，仓库不提供私钥或成功占位。</p>
 */
@Service
@RequiredArgsConstructor
public class LotDataPackageService {
    private static final int MAX_RECORDS = 100_000;
    private final LotInventoryMapper mapper;
    private final LotPolicyReadPort policyReadPort;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final StoreIndustryReadPort storeIndustryReadPort;
    private final ObjectProvider<PackageSigningPort> signingPorts;
    private final ObjectMapper objectMapper;
    private final UlidGenerator ulids;
    private final Clock clock;

    /** 使用 releaseId 幂等发布新批次包；版本只在本门店仓库内单调递增。 */
    @Transactional
    public PackageArtifact publish(Long storeId, String warehouseId, String releaseId, String correlationId) {
        InventoryRules.requireUlid(warehouseId, "warehouseId");
        InventoryRules.requireUlid(releaseId, "releaseId");
        if (storeId == null || storeId <= 0 || correlationId == null || correlationId.isBlank()
            || correlationId.length() > 96) throw new ServiceException("LOT-DPK-001: 发布参数非法", 400);
        authorization.requireStoreAccess(storeId);
        String tenantId = tenantContext.requireTenantId();
        StoreIndustryReadPort.IndustryBinding industry = storeIndustryReadPort.requireCurrentIndustry(storeId);
        if (!"COMMUNITY_SUPERMARKET".equals(industry.industry())) {
            throw new ServiceException("LOT-DPK-006: 非社区超市门店禁止生成批次数据包", 409);
        }
        Instant at = clock.instant();
        List<PolicyView> policies = policyReadPort.listEffective(storeId, at);
        List<LotView> lots = mapper.findPackageLots(tenantId, storeId, warehouseId, MAX_RECORDS + 1);
        if (policies.isEmpty() || policies.size() + lots.size() > MAX_RECORDS) {
            throw new ServiceException("LOT-DPK-002: 批次包缺少策略或超过 100k 记录", 409);
        }
        String sourceSha256 = CatalogPackageCodec.sha256(json(sourceDocument(storeId, warehouseId, industry,
            policies, lots))
            .getBytes(StandardCharsets.UTF_8));
        LotPackageRelease replay = mapper.findPackageByRelease(tenantId, releaseId);
        if (replay != null) return replay(replay, sourceSha256);
        PackageSigningPort signingPort = signingPorts.getIfAvailable();
        if (signingPort == null) {
            throw new ServiceException("LOT-DPK-003: KMS/HSM 签名端口未配置", 503);
        }
        LotPackageRelease previous = mapper.lockLatestPackage(tenantId, storeId, warehouseId);
        long previousVersion = previous == null ? 0 : previous.packageVersion();
        long packageVersion = previousVersion + 1;
        LocalDateTime generatedAt = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        byte[] payload = json(document(tenantId, storeId, warehouseId, packageVersion, previousVersion,
            at, industry, policies, lots))
            .getBytes(StandardCharsets.UTF_8);
        String sha256 = CatalogPackageCodec.sha256(payload);
        PackageSigningPort.SigningResult signed = signingPort.sign(tenantId, payload);
        if (signed.signature().length != 64) throw new ServiceException("LOT-DPK-004: Ed25519 签名长度非法", 500);
        try {
            mapper.insertPackageRelease(new LotPackageWrite(releaseId, tenantId, storeId, warehouseId,
                packageVersion, previousVersion, sourceSha256, sha256, payload, signed.keyId(), signed.signature(),
                policies.size() + lots.size(), generatedAt));
        } catch (DuplicateKeyException exception) {
            LotPackageRelease raced = mapper.findPackageByRelease(tenantId, releaseId);
            if (raced != null) return replay(raced, sourceSha256);
            throw new ServiceException("LOT-DPK-009: 批次包版本并发冲突，请使用新发布命令重试", 409);
        }
        var principal = tenantContext.requirePrincipal();
        mapper.insertAudit(new AuditWrite(ulids.next(), tenantId, storeId, "LOT_PACKAGE_PUBLISHED", warehouseId,
            principal.userId(), releaseId, correlationId, sourceSha256, "SIGNED_RELEASE", generatedAt));
        String eventPayload = json(Map.of("releaseId", releaseId, "storeId", storeId,
            "warehouseId", warehouseId, "packageVersion", packageVersion, "previousVersion", previousVersion,
            "payloadSha256", sha256, "recordCount", policies.size() + lots.size()));
        mapper.insertOutbox(new OutboxWrite(ulids.next(), tenantId, "inventory.lot.package-published.v1",
            warehouseId, packageVersion, correlationId, eventPayload,
            CatalogPackageCodec.sha256(eventPayload.getBytes(StandardCharsets.UTF_8)), generatedAt));
        return new PackageArtifact(payload, sha256, signed.keyId(), signed.signature());
    }

    /** 读取最后一次已封存包；读取不会隐式创建新版本。 */
    @Transactional(readOnly = true)
    public PackageArtifact latest(Long storeId, String warehouseId) {
        InventoryRules.requireUlid(warehouseId, "warehouseId");
        if (storeId == null || storeId <= 0) throw new ServiceException("LOT-DPK-001: 门店参数非法", 400);
        authorization.requireStoreAccess(storeId);
        String tenantId = tenantContext.requireTenantId();
        StoreIndustryReadPort.IndustryBinding industry = storeIndustryReadPort.requireCurrentIndustry(storeId);
        if (!"COMMUNITY_SUPERMARKET".equals(industry.industry())) {
            throw new ServiceException("LOT-DPK-006: 非社区超市门店禁止下载批次数据包", 409);
        }
        LotPackageRelease release = mapper.findLatestPackage(tenantId, storeId, warehouseId);
        if (release == null) throw new ServiceException("LOT-DPK-008: 尚未发布批次数据包", 404);
        requireCurrentRelease(release, tenantId, storeId, warehouseId, industry);
        return artifact(release);
    }

    private void requireCurrentRelease(LotPackageRelease release, String tenantId, Long storeId, String warehouseId,
                                       StoreIndustryReadPort.IndustryBinding industry) {
        try {
            String payloadHash = CatalogPackageCodec.sha256(release.payloadBytes());
            var root = objectMapper.readTree(release.payloadBytes());
            boolean valid = payloadHash.equals(release.payloadSha256())
                && release.signatureBytes() != null && release.signatureBytes().length == 64
                && release.signingKeyId() != null && !release.signingKeyId().isBlank()
                && tenantId.equals(root.path("tenantId").asText())
                && storeId.toString().equals(root.path("storeId").asText())
                && warehouseId.equals(root.path("warehouseId").asText())
                && "COMMUNITY_SUPERMARKET".equals(root.path("industry").asText())
                && industry.templateVersionId().toString().equals(root.path("industryTemplateVersionId").asText())
                && industry.contentSha256().equals(root.path("industryTemplateSha256").asText())
                && industry.zoneId().equals(root.path("businessZoneId").asText())
                && industry.businessDayStart().toString().equals(root.path("businessDayStart").asText())
                && release.packageVersion() == root.path("packageVersion").asLong(-1)
                && release.previousVersion() == root.path("previousVersion").asLong(-1);
            if (!valid) throw new ServiceException("LOT-DPK-010: 最后发布包损坏或与当前门店模板不兼容", 409);
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("LOT-DPK-010: 最后发布包损坏或与当前门店模板不兼容", 409);
        }
    }

    private Map<String, Object> document(String tenantId, Long storeId, String warehouseId, long packageVersion,
                                         long previousVersion, Instant generatedAt,
                                         StoreIndustryReadPort.IndustryBinding industry,
                                         List<PolicyView> policies, List<LotView> lots) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", "1.0");
        value.put("tenantId", tenantId);
        value.put("storeId", storeId.toString());
        value.put("warehouseId", warehouseId);
        value.put("industry", "COMMUNITY_SUPERMARKET");
        value.put("industryTemplateVersionId", industry.templateVersionId().toString());
        value.put("industryTemplateSha256", industry.contentSha256());
        value.put("businessZoneId", industry.zoneId());
        value.put("businessDayStart", industry.businessDayStart().toString());
        value.put("packageVersion", packageVersion);
        value.put("previousVersion", previousVersion);
        value.put("generatedAt", generatedAt.toString());
        value.put("policies", policies.stream().map(this::policy).toList());
        value.put("lots", lots.stream().map(this::lot).toList());
        return value;
    }

    private Map<String, Object> sourceDocument(Long storeId, String warehouseId,
                                               StoreIndustryReadPort.IndustryBinding industry,
                                               List<PolicyView> policies, List<LotView> lots) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("storeId", storeId.toString());
        value.put("warehouseId", warehouseId);
        value.put("industryTemplateVersionId", industry.templateVersionId().toString());
        value.put("industryTemplateSha256", industry.contentSha256());
        value.put("businessZoneId", industry.zoneId());
        value.put("businessDayStart", industry.businessDayStart().toString());
        value.put("policies", policies.stream().map(this::policy).toList());
        value.put("lots", lots.stream().map(this::lot).toList());
        return value;
    }

    private PackageArtifact replay(LotPackageRelease release, String expectedSourceSha256) {
        if (!expectedSourceSha256.equals(release.sourceSha256())) {
            throw new ServiceException("LOT-DPK-007: 相同 releaseId 对应不同批次事实", 409);
        }
        return artifact(release);
    }

    private static PackageArtifact artifact(LotPackageRelease release) {
        return new PackageArtifact(release.payloadBytes(), release.payloadSha256(), release.signingKeyId(),
            release.signatureBytes());
    }

    private Map<String, Object> policy(PolicyView policy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("policyVersionId", policy.policyVersionId());
        value.put("skuId", policy.skuId().toString());
        value.put("enabled", policy.enabled());
        value.put("expiryBasis", policy.expiryBasis());
        value.put("shelfLifeDays", policy.shelfLifeDays());
        value.put("nearExpiryDays", policy.nearExpiryDays());
        value.put("effectiveFrom", policy.effectiveFrom().toString());
        value.put("contentSha256", policy.contentSha256());
        return value;
    }

    private Map<String, Object> lot(LotView lot) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("lotId", lot.lotId());
        value.put("skuId", lot.skuId().toString());
        value.put("baseUnitId", lot.baseUnitId().toString());
        value.put("supplierLotCode", lot.supplierLotCode());
        value.put("internalLotCode", lot.internalLotCode());
        value.put("productionDate", lot.productionDate() == null ? null : lot.productionDate().toString());
        value.put("receivedDate", lot.receivedDate().toString());
        value.put("expiryDate", lot.expiryDate().toString());
        value.put("policyVersionId", lot.policyVersionId());
        value.put("nearExpiryDays", lot.nearExpiryDays());
        value.put("quantity", lot.onHandQuantity().stripTrailingZeros().toPlainString());
        value.put("lastLedgerSequence", lot.lastLedgerSequence());
        value.put("sourceSha256", InventoryHash.sha256(InventoryHash.canonical(List.of(lot.lotId(),
            lot.onHandQuantity(), lot.lastLedgerSequence(), lot.policyVersionId(), lot.expiryDate()))));
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("LOT-DPK-005: 批次包序列化失败", 500);
        }
    }
}
