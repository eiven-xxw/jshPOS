package com.jingshanghui.pos.promotion.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.security.TenantResourceNamespace;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageView;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageArtifact;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.ObjectPort;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.SigningPort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.PackageWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.PackageItemWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.AuditWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.OutboxWrite;
import com.jingshanghui.pos.promotion.domain.PromotionEngine;
import com.jingshanghui.pos.promotion.domain.PromotionPackageCodec;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 构建、签名、存储和查询门店绑定的不可变促销规则包。 */
@Service
@RequiredArgsConstructor
public class PromotionPackageService {
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final TenantResourceNamespace namespace;
    private final PromotionPersistencePort persistence;
    private final PromotionRuleDefinitionCodec definitionCodec;
    private final ManualPolicyCodec manualPolicyCodec;
    private final ObjectProvider<SigningPort> signers;
    private final ObjectProvider<ObjectPort> objects;
    private final PromotionIdGenerator ids;
    private final Clock clock;

    /** 发布严格连续的促销规则包；缺失 KMS/HSM 或对象存储时失败关闭。 */
    @Transactional
    public PackageView publish(Long storeId, long packageVersion, long previousVersion, Instant expiresAt,
                               String correlationId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String tenantId = principal.tenantId();
        authorization.requireStoreAccess(storeId);
        if (correlationId == null || !correlationId.matches("^[0-9A-HJKMNP-TV-Z]{26}$")) {
            throw new ServiceException("PRM-PKG-014: 关联标识无效", 400);
        }
        SigningPort signer = signers.getIfAvailable(); ObjectPort object = objects.getIfAvailable();
        if (signer == null || object == null) throw new ServiceException("PRM-PKG-010: KMS/HSM或对象存储未配置",503);
        PackageView latest = persistence.findLatestPackage(tenantId, storeId);
        long expectedPrevious = latest == null ? 0 : latest.packageVersion();
        if (previousVersion != expectedPrevious || packageVersion != expectedPrevious + 1) {
            throw new ServiceException("PRM-PKG-011: 规则包版本必须严格连续",409);
        }
        Instant generatedAt = clock.instant();
        if (expiresAt == null || !expiresAt.isAfter(generatedAt)) throw new ServiceException("PRM-PKG-012: 过期时间无效",400);
        List<FrozenRule> frozenRules = new ArrayList<>();
        for (var row : persistence.listPackageRuleDefinitions(tenantId, storeId,
            LocalDateTime.ofInstant(generatedAt, ZoneOffset.UTC),
            LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC))) {
            var rule = definitionCodec.fromRow(row);
            var canonical = definitionCodec.canonical(rule);
            frozenRules.add(new FrozenRule(rule.ruleVersionId(), canonical.json(), canonical.sha256()));
        }
        frozenRules.sort(Comparator.comparing(FrozenRule::ruleVersionId));
        List<PromotionPackageCodec.Record> records = frozenRules.stream()
            .map(value -> new PromotionPackageCodec.Record(value.ruleVersionId(), value.canonicalRule())).toList();
        var policyRow = persistence.findManualPolicy(tenantId, storeId);
        var policy = manualPolicyCodec.decode(policyRow);
        CanonicalJson.Result canonicalPolicy = CanonicalJson.from(Map.of(
            "policyType", "PROMOTION_MANUAL_AUTHORITY",
            "withoutApprovalMinor", policy.withoutApprovalMinor(),
            "withApprovalMinor", policy.withApprovalMinor(),
            "minimumLinePayableMinor", policy.minimumLinePayableMinor(),
            "maximumRoundingMinor", policy.maximumRoundingMinor(),
            "roundingMultiplesMinor", policy.roundingMultiplesMinor()));
        if (!canonicalPolicy.sha256().equals(policy.policySha256())) {
            throw new ServiceException("PRM-PKG-018: 人工优惠策略规范摘要不一致", 500);
        }
        var manualPolicy = new PromotionPackageCodec.ManualPolicyRecord(policyRow.policyVersionId(),
            policyRow.contentSha256(), canonicalPolicy.json());
        var encoded = PromotionPackageCodec.encode(tenantId, storeId, packageVersion, previousVersion,
            generatedAt, expiresAt, records, manualPolicy);
        var signed = signer.sign(tenantId, encoded.payload());
        if (signed == null || signed.keyId() == null || signed.keyId().isBlank()
            || signed.signature().length != 64) {
            throw new ServiceException("PRM-PKG-015: Ed25519签名结果无效", 503);
        }
        String resource = "promotion-" + storeId + "-" + packageVersion + "-" + encoded.sha256() + ".jshpkg";
        String objectKey = namespace.objectKey(resource);
        object.put(objectKey, encoded.payload(), signed.signature());
        String packageId = ids.next();
        persistence.insertPackage(new PackageWrite(tenantId, packageId, storeId, packageVersion, previousVersion,
            PromotionPackageCodec.SCHEMA_VERSION, PromotionEngine.ENGINE_VERSION, encoded.sha256(), signed.keyId(),
            objectKey, encoded.recordCount(), LocalDateTime.ofInstant(generatedAt, ZoneOffset.UTC),
            LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)));
        for (int index = 0; index < records.size(); index++) {
            var record = records.get(index);
            persistence.insertPackageItem(new PackageItemWrite(tenantId, ids.next(), packageId, storeId,
                packageVersion, index + 1, record.ruleVersionId(), frozenRules.get(index).contentSha256()));
        }
        appendAuditAndOutbox(principal, packageId, packageVersion, encoded.sha256(), correlationId);
        return require(storeId, packageVersion);
    }

    /** 按可信租户和门店范围查询规则包。 */
    @Transactional(readOnly=true)
    public PackageView require(Long storeId, long packageVersion) {
        String tenantId=tenantContext.requireTenantId(); authorization.requireStoreAccess(storeId);
        PackageView result=persistence.findPackage(tenantId,storeId,packageVersion);
        if(result==null) throw new ServiceException("PRM-PKG-013: 规则包不存在或不可见",404);
        return result;
    }

    /** 从可信对象命名空间读取原始载荷和签名，供 POS 使用预置公钥再次验证。 */
    @Transactional(readOnly=true)
    public PackageArtifact download(Long storeId, long packageVersion) {
        PackageView metadata = require(storeId, packageVersion);
        ObjectPort object = objects.getIfAvailable();
        if (object == null) throw new ServiceException("PRM-PKG-010: 对象存储未配置", 503);
        var stored = object.get(metadata.objectKey());
        if (stored == null || stored.payload().length == 0 || stored.signature().length != 64
            || !PromotionPackageCodec.sha256(stored.payload()).equals(metadata.payloadSha256())) {
            throw new ServiceException("PRM-PKG-016: 规则包对象缺失或摘要损坏", 500);
        }
        return new PackageArtifact(stored.payload(), metadata.payloadSha256(), metadata.signingKeyId(),
            stored.signature());
    }

    private void appendAuditAndOutbox(TrustedPrincipal principal, String packageId, long packageVersion,
                                      String payloadSha256, String correlationId) {
        LocalDateTime now = LocalDateTime.now(clock);
        persistence.insertAudit(new AuditWrite(principal.tenantId(), ids.next(), "PROMOTION_PACKAGE_PUBLISHED",
            "PROMOTION_PACKAGE", packageId, principal.userId(), correlationId, null, payloadSha256,
            "{\"action\":\"PROMOTION_PACKAGE_PUBLISHED\"}", now));
        CanonicalJson.Result event = CanonicalJson.from(Map.of("action", "PROMOTION_PACKAGE_PUBLISHED",
            "targetId", packageId, "version", packageVersion));
        persistence.insertOutbox(new OutboxWrite(principal.tenantId(), ids.next(), "promotion.package.published.v1",
            "PROMOTION_PACKAGE", packageId, packageVersion, event.json(), event.sha256(), now));
    }

    /** 包发布事务内冻结的规则AST与摘要。 */
    private record FrozenRule(String ruleVersionId, String canonicalRule, String contentSha256) { }
}
