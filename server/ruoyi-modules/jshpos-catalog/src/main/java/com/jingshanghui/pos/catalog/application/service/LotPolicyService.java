package com.jingshanghui.pos.catalog.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PolicyView;
import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PublishCommand;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort;
import com.jingshanghui.pos.catalog.application.port.LotPolicyReadPort;
import com.jingshanghui.pos.catalog.application.port.LotPolicyTransitionGuardPort;
import com.jingshanghui.pos.catalog.domain.LotExpiryRules;
import com.jingshanghui.pos.catalog.domain.LotExpiryRules.PolicySpec;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.LotPolicyMapper;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.LotPolicyMapper.PolicyWrite;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort.IndustryBinding;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/** Catalog Owner 发布和读取社区超市批次效期策略。 */
@Service
@RequiredArgsConstructor
public class LotPolicyService implements LotPolicyReadPort {
    private final LotPolicyMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final StoreIndustryReadPort industryReadPort;
    private final InventoryCatalogSnapshotPort catalogSnapshotPort;
    private final LotPolicyTransitionGuardPort transitionGuard;
    private final DomainAuditService audit;
    private final CatalogOutboxService outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 发布版本即不可变；停用通过发布 enabled=false 的新版本表达。 */
    @Transactional
    public PolicyView publish(PublishCommand command) {
        if (command == null || command.correlationId() == null || command.correlationId().isBlank()
            || command.correlationId().length() > 96) {
            throw new ServiceException("CAT-LOT-005: 命令或关联标识非法", 400);
        }
        PolicySpec policy = LotExpiryRules.requirePolicy(new PolicySpec(command.policyVersionId(),
            command.storeId(), command.skuId(), command.enabled(), normalize(command.expiryBasis()),
            command.shelfLifeDays(), command.nearExpiryDays(), command.effectiveFrom()));
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorization.requireTenantAdministrator();
        authorization.requireStoreAccess(policy.storeId());
        IndustryBinding binding = industryReadPort.requireCurrentIndustry(policy.storeId());
        if (policy.enabled() && !LotExpiryRules.COMMUNITY_SUPERMARKET.equals(binding.industry())) {
            throw new ServiceException("CAT-LOT-006: 只有社区超市行业模板允许启用批次效期", 409);
        }
        catalogSnapshotPort.requirePrimaryUnit(policy.skuId());
        if (!policy.enabled()) transitionGuard.requireCanDisable(policy.storeId(), policy.skuId());
        String hash = LotExpiryRules.contentSha256(policy);
        PolicyView existing = mapper.findById(principal.tenantId(), policy.policyVersionId());
        if (existing != null) {
            if (!hash.equals(existing.contentSha256())) {
                throw new ServiceException("CAT-LOT-007: 相同策略版本对应不同内容", 409);
            }
            return existing;
        }
        LocalDateTime now = utc(clock.instant());
        mapper.insertPublished(new PolicyWrite(principal.tenantId(), policy.policyVersionId(), policy.storeId(),
            policy.skuId(), policy.enabled(), policy.expiryBasis(), policy.shelfLifeDays(), policy.nearExpiryDays(),
            binding.industry(), binding.templateVersionId(), utc(policy.effectiveFrom()), hash,
            principal.userId(), now));
        PolicyView result = requireById(principal.tenantId(), policy.policyVersionId());
        audit.append("LOT_POLICY_PUBLISHED", "LOT_POLICY", policy.policyVersionId(), null, result,
            Map.of("storeId", policy.storeId(), "skuId", policy.skuId(), "enabled", policy.enabled(),
                "contentSha256", hash, "correlationId", command.correlationId()));
        outbox.append(principal.tenantId(), "catalog.lot-policy.published.v1", "LOT_POLICY", policy.skuId(),
            Math.max(1, policy.effectiveFrom().getEpochSecond()), json(Map.of("policyVersionId",
                policy.policyVersionId(), "storeId", policy.storeId(), "skuId", policy.skuId(),
                "enabled", policy.enabled(), "industry", binding.industry(), "contentSha256", hash,
                "correlationId", command.correlationId())));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PolicyView> findEffective(Long storeId, Long skuId, Instant effectiveAt) {
        if (storeId == null || storeId <= 0 || skuId == null || skuId <= 0) {
            throw new ServiceException("CAT-LOT-008: 门店或 SKU 非法", 400);
        }
        authorization.requireStoreAccess(storeId);
        PolicyView result = mapper.findEffective(tenantContext.requireTenantId(), storeId, skuId,
            utc(effectiveAt == null ? clock.instant() : effectiveAt));
        if (result == null) return Optional.empty();
        if (result.enabled() && !LotExpiryRules.COMMUNITY_SUPERMARKET.equals(result.industry())) {
            throw new ServiceException("CAT-LOT-010: 生效策略行业身份非法", 409);
        }
        return Optional.of(result);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyView requireEffective(Long storeId, Long skuId, Instant effectiveAt) {
        return findEffective(storeId, skuId, effectiveAt)
            .orElseThrow(() -> new ServiceException("CAT-LOT-009: 当前没有生效批次策略", 404));
    }

    /** 批次数据包只读端口；行业身份异常时整包失败关闭。 */
    @Override
    @Transactional(readOnly = true)
    public List<PolicyView> listEffective(Long storeId, Instant effectiveAt) {
        if (storeId == null || storeId <= 0) throw new ServiceException("CAT-LOT-008: 门店或 SKU 非法", 400);
        authorization.requireStoreAccess(storeId);
        List<PolicyView> policies = mapper.listEffective(tenantContext.requireTenantId(), storeId,
            utc(effectiveAt == null ? clock.instant() : effectiveAt));
        if (policies.stream().anyMatch(policy -> policy.enabled()
            && !LotExpiryRules.COMMUNITY_SUPERMARKET.equals(policy.industry()))) {
            throw new ServiceException("CAT-LOT-010: 生效策略行业身份非法", 409);
        }
        return List.copyOf(policies);
    }

    private PolicyView requireById(String tenantId, String policyVersionId) {
        PolicyView result = mapper.findById(tenantId, policyVersionId);
        if (result == null) throw new ServiceException("CAT-LOT-011: 策略写入后不可见", 500);
        return result;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("CAT-LOT-012: 策略事件序列化失败", 500);
        }
    }

    private static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase();
    }
}
