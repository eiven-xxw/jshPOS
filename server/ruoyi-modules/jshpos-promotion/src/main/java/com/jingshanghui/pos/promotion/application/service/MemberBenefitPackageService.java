package com.jingshanghui.pos.promotion.application.service;

import com.jingshanghui.pos.catalog.application.port.MemberPricePackageSourcePort;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.security.TenantResourceNamespace;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.member.application.port.MemberBenefitPackageSourcePort;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.MemberBenefitPackageView;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageArtifact;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.ObjectPort;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.SigningPort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.domain.MemberBenefitPackageCodec;
import com.jingshanghui.pos.promotion.domain.PromotionPackageCodec;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Map;

import static com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.AuditWrite;
import static com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.OutboxWrite;

/** 通过 Member/Pricing 正式只读端口组装、签名并持久化离线权益包。 */
@Service
@RequiredArgsConstructor
public class MemberBenefitPackageService {
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final TenantResourceNamespace namespace;
    private final MemberBenefitPackageSourcePort benefitSource;
    private final MemberPricePackageSourcePort priceSource;
    private final PromotionPersistencePort persistence;
    private final ObjectProvider<SigningPort> signers;
    private final ObjectProvider<ObjectPort> objects;
    private final PromotionIdGenerator ids;
    private final Clock clock;

    /** 发布严格连续、门店绑定、无会员身份的签名数据包。 */
    @Transactional
    public MemberBenefitPackageView publish(Long storeId, long packageVersion, long previousVersion,
                                            Instant expiresAt, String correlationId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorization.requireStoreAccess(storeId);
        if (correlationId == null || !correlationId.matches("^[0-9A-HJKMNP-TV-Z]{26}$")) {
            throw new ServiceException("PRM-MBP-PKG-004: 关联标识无效", 400);
        }
        SigningPort signer = signers.getIfAvailable(); ObjectPort object = objects.getIfAvailable();
        if (signer == null || object == null) throw new ServiceException("PRM-MBP-PKG-005: 签名或对象存储未配置",503);
        MemberBenefitPackageView latest = persistence.findLatestMemberBenefitPackage(principal.tenantId(), storeId);
        long expectedPrevious = latest == null ? 0 : latest.packageVersion();
        if (previousVersion != expectedPrevious || packageVersion != expectedPrevious + 1) {
            throw new ServiceException("PRM-MBP-PKG-006: 包版本必须严格连续",409);
        }
        Instant now = clock.instant();
        if (expiresAt == null || !expiresAt.isAfter(now)) throw new ServiceException("PRM-MBP-PKG-007: 过期时间无效",400);
        LocalDateTime from = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        LocalDateTime to = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC);
        var benefits = benefitSource.listForPackage(principal.tenantId(), storeId, from, to).stream()
            .map(row -> new MemberBenefitPackageCodec.BenefitRecord(row.versionId(), row.levelCode(),
                row.memberPriceEligible(), row.stackingAllowed(), row.defaultCombinationPolicy(),
                row.policyAllowStacking(), row.revocationEpoch(), row.effectiveAt(), row.expiresAt(),
                row.contentSha256())).toList();
        var prices = priceSource.listForPackage(principal.tenantId(), storeId, from, to).stream()
            .map(row -> new MemberBenefitPackageCodec.MemberPriceRecord(row.versionId(), row.versionNo(),
                row.levelCode(), row.skuId(), row.unitId(), row.scopeStoreId(), row.amountMinor(),
                row.effectiveAt(), row.expiresAt(), row.contentSha256())).toList();
        var encoded = MemberBenefitPackageCodec.encode(principal.tenantId(), storeId, packageVersion,
            previousVersion, now, expiresAt, benefits, prices);
        var signed = signer.sign(principal.tenantId(), encoded.payload());
        if (signed == null || signed.keyId() == null || signed.keyId().isBlank() || signed.signature().length != 64) {
            throw new ServiceException("PRM-MBP-PKG-008: 签名结果无效",503);
        }
        String objectKey = namespace.objectKey("member-benefit-" + storeId + "-" + packageVersion
            + "-" + encoded.sha256() + ".jshpkg");
        object.put(objectKey, encoded.payload(), signed.signature());
        String packageId = ids.next();
        persistence.insertMemberBenefitPackage(new PromotionPersistencePort.MemberBenefitPackageWrite(
            principal.tenantId(), packageId, storeId, packageVersion, previousVersion, encoded.sha256(),
            signed.keyId(), objectKey, encoded.benefitCount(), encoded.memberPriceCount(), from, to));
        appendAuditAndOutbox(principal, packageId, storeId, packageVersion, encoded.sha256(), correlationId);
        return require(storeId, packageVersion);
    }

    @Transactional(readOnly = true)
    public MemberBenefitPackageView require(Long storeId, long packageVersion) {
        String tenantId = tenantContext.requireTenantId(); authorization.requireStoreAccess(storeId);
        MemberBenefitPackageView value = persistence.findMemberBenefitPackage(tenantId, storeId, packageVersion);
        if (value == null) throw new ServiceException("PRM-MBP-PKG-009: 数据包不存在",404);
        return value;
    }

    @Transactional(readOnly = true)
    public PackageArtifact download(Long storeId, long packageVersion) {
        MemberBenefitPackageView metadata = require(storeId, packageVersion);
        ObjectPort object = objects.getIfAvailable();
        if (object == null) throw new ServiceException("PRM-MBP-PKG-005: 对象存储未配置",503);
        var stored = object.get(metadata.objectKey());
        if (stored == null || stored.payload().length == 0 || stored.signature().length != 64
            || !PromotionPackageCodec.sha256(stored.payload()).equals(metadata.payloadSha256())) {
            throw new ServiceException("PRM-MBP-PKG-010: 对象缺失或摘要损坏",500);
        }
        return new PackageArtifact(stored.payload(), metadata.payloadSha256(), metadata.signingKeyId(), stored.signature());
    }

    /** 发布事实、审计与数据包元数据在同一事务内提交，避免出现不可追溯的离线版本。 */
    private void appendAuditAndOutbox(TrustedPrincipal principal, String packageId, Long storeId,
                                      long packageVersion, String payloadSha256, String correlationId) {
        LocalDateTime now = LocalDateTime.now(clock);
        CanonicalJson.Result fact = CanonicalJson.from(Map.of(
            "action", "MEMBER_BENEFIT_PACKAGE_PUBLISHED",
            "packageId", packageId,
            "storeId", storeId,
            "packageVersion", packageVersion,
            "payloadSha256", payloadSha256));
        persistence.insertAudit(new AuditWrite(principal.tenantId(), ids.next(),
            "MEMBER_BENEFIT_PACKAGE_PUBLISHED", "MEMBER_BENEFIT_PACKAGE", packageId,
            principal.userId(), correlationId, null, payloadSha256, fact.json(), now));
        persistence.insertOutbox(new OutboxWrite(principal.tenantId(), ids.next(),
            "promotion.member-benefit-package.published.v1", "MEMBER_BENEFIT_PACKAGE",
            packageId, packageVersion, fact.json(), fact.sha256(), now));
    }
}
