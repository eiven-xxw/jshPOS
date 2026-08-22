package com.jingshanghui.pos.catalog.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.WeightedBarcodePreview;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.WeightedBarcodeTemplateView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.WeightedSkuView;
import com.jingshanghui.pos.catalog.application.port.WeightedBarcodeSnapshotVerificationPort;
import com.jingshanghui.pos.catalog.application.port.WeightedBarcodeSnapshotVerificationPort.FrozenMeasurement;
import com.jingshanghui.pos.catalog.application.price.PriceResolution.ResolvedPrice;
import com.jingshanghui.pos.catalog.domain.CatalogRules;
import com.jingshanghui.pos.catalog.domain.WeightedBarcodeRules;
import com.jingshanghui.pos.catalog.domain.WeightedBarcodeRules.ParsedMeasurement;
import com.jingshanghui.pos.catalog.domain.WeightedBarcodeRules.Template;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * T2-PRD-005 秤码模板应用服务。
 *
 * <p>服务只编排可信租户、权限、模板状态和价格只读端口；解析与金额规则全部位于纯领域类。</p>
 */
@Service
@RequiredArgsConstructor
public class WeightedBarcodeService implements WeightedBarcodeSnapshotVerificationPort {

    private final CatalogMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final DomainAuditService auditService;
    private final PriceBookService priceBookService;
    private final CatalogOutboxService outboxService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /** 创建不可直接生效的 DRAFT 模板。 */
    @Transactional
    public WeightedBarcodeTemplateView create(CreateTemplate command) {
        String tenantId = tenantContext.requireTenantId();
        String scope = requireScope(command.scopeType(), command.storeId());
        requireStoreAccess(command.storeId());
        Long templateId = IdWorker.getId();
        Template template = WeightedBarcodeRules.requireTemplate(new Template(templateId,
            CatalogRules.requireCode(command.templateCode(), "CAT-WBC-020"), command.versionNo(), scope,
            command.storeId(), normalize(command.barcodeKind()), "EAN13", command.prefixValue(), 13,
            command.skuStartPos(), command.skuLength(), command.valueStartPos(), command.valueLength(),
            command.valueScale(), command.priorityNo(), command.effectiveFrom(), command.effectiveTo(), null));
        mapper.insertWeightedBarcodeTemplate(tenantId, templateId, template.templateCode(), template.versionNo(),
            scope, template.storeId(), template.kind(), template.prefix(), template.skuStart(), template.skuLength(),
            template.valueStart(), template.valueLength(), template.valueScale(), template.priority(),
            utc(template.effectiveFrom()), utc(template.effectiveTo()));
        WeightedBarcodeTemplateView result = requireTemplate(tenantId, templateId);
        auditService.append("WEIGHTED_BARCODE_TEMPLATE_CREATED", "WEIGHTED_BARCODE_TEMPLATE", templateId,
            null, result, Map.of("scope", scope, "kind", template.kind(), "versionNo", template.versionNo()));
        return result;
    }

    /** 发布后内容不可变；同范围、同前缀、重叠时间窗的模板拒绝发布。 */
    @Transactional
    public WeightedBarcodeTemplateView publish(Long templateId, int expectedVersion) {
        String tenantId = tenantContext.requireTenantId();
        WeightedBarcodeTemplateView before = requireTemplate(tenantId, templateId);
        requireStoreAccess(before.storeId());
        if (!"DRAFT".equals(before.state()) || !Integer.valueOf(expectedVersion).equals(before.version())) {
            throw new ServiceException("CAT-WBC-021: 仅匹配版本的 DRAFT 模板可发布", 409);
        }
        Template template = toDomain(before);
        if (mapper.countWeightedBarcodeConflict(tenantId, templateId, before.scopeType(), before.storeId(),
            before.prefixValue(), utc(before.effectiveFrom()), utc(before.effectiveTo())) > 0) {
            throw new ServiceException("CAT-WBC-022: 同范围同前缀存在重叠的已发布模板", 409);
        }
        String hash = WeightedBarcodeRules.contentSha256(template);
        LocalDateTime now = utc(clock.instant());
        if (mapper.publishWeightedBarcodeTemplate(tenantId, templateId, expectedVersion, hash, now) != 1) {
            throw new ServiceException("CAT-WBC-023: 模板发布发生并发冲突", 409);
        }
        WeightedBarcodeTemplateView after = requireTemplate(tenantId, templateId);
        appendHistory(tenantId, after, "PUBLISHED", hash, now);
        auditService.append("WEIGHTED_BARCODE_TEMPLATE_PUBLISHED", "WEIGHTED_BARCODE_TEMPLATE", templateId,
            before, after, Map.of("contentSha256", hash));
        outboxService.append(tenantId, "weighted-barcode-template.published.v1", "WEIGHTED_BARCODE_TEMPLATE",
            templateId, after.versionNo(), json(Map.of("templateId", templateId,
                "versionNo", after.versionNo(), "contentSha256", hash)));
        return after;
    }

    /** 退役只改变可选择状态，不删除或回写已成交快照。 */
    @Transactional
    public WeightedBarcodeTemplateView retire(Long templateId, int expectedVersion) {
        String tenantId = tenantContext.requireTenantId();
        WeightedBarcodeTemplateView before = requireTemplate(tenantId, templateId);
        requireStoreAccess(before.storeId());
        if (!"PUBLISHED".equals(before.state()) || !Integer.valueOf(expectedVersion).equals(before.version())
            || mapper.retireWeightedBarcodeTemplate(tenantId, templateId, expectedVersion) != 1) {
            throw new ServiceException("CAT-WBC-024: 仅匹配版本的 PUBLISHED 模板可退役", 409);
        }
        WeightedBarcodeTemplateView after = requireTemplate(tenantId, templateId);
        LocalDateTime now = utc(clock.instant());
        appendHistory(tenantId, after, "RETIRED", before.contentSha256(), now);
        auditService.append("WEIGHTED_BARCODE_TEMPLATE_RETIRED", "WEIGHTED_BARCODE_TEMPLATE", templateId,
            before, after, Map.of("contentSha256", before.contentSha256()));
        return after;
    }

    /** 以与 POS 相同的确定性优先级进行解析预览。 */
    @Transactional(readOnly = true)
    public WeightedBarcodePreview preview(Long storeId, String rawBarcode, Instant at) {
        String tenantId = tenantContext.requireTenantId();
        authorizationService.requireStoreAccess(storeId);
        Instant effectiveAt = at == null ? clock.instant() : at;
        List<WeightedBarcodeTemplateView> candidates = mapper.listWeightedBarcodeCandidates(tenantId, storeId,
            rawBarcode, utc(effectiveAt));
        WeightedBarcodeTemplateView selected = selectOne(candidates);
        Template template = toDomain(selected);
        String skuCode = rawBarcode == null || rawBarcode.length() != 13 ? "" : rawBarcode.substring(
            template.skuStart() - 1, template.skuStart() - 1 + template.skuLength());
        WeightedSkuView sku = mapper.findWeightedSkuByCode(tenantId, skuCode);
        if (sku == null) {
            throw new ServiceException("CAT-WBC-025: 条码中的计量商品不存在或未启用", 404);
        }
        ResolvedPrice price = priceBookService.resolve(sku.skuId(), sku.unitId(), storeId, effectiveAt);
        ParsedMeasurement parsed = WeightedBarcodeRules.parse(template, rawBarcode, price.amountMinor(),
            sku.decimalScale(), effectiveAt);
        return new WeightedBarcodePreview(parsed.rawBarcode(), sku.skuId(), parsed.skuCode(), sku.unitId(),
            parsed.quantity(), parsed.amountMinor(), parsed.unitPriceMinor(), parsed.currency(), parsed.templateId(),
            parsed.templateVersion(), parsed.templateSha256(), parsed.parseSha256(), parsed.roundingApplied(),
            parsed.occurredAt());
    }

    @Transactional(readOnly = true)
    public WeightedBarcodeTemplateView get(Long templateId) {
        String tenantId = tenantContext.requireTenantId();
        WeightedBarcodeTemplateView result = requireTemplate(tenantId, templateId);
        requireStoreAccess(result.storeId());
        return result;
    }

    /** 验证订单携带的冻结计量快照；退役模板仍可验证其有效期内形成的历史成交。 */
    @Override
    @Transactional(readOnly = true)
    public void verify(Long storeId, FrozenMeasurement snapshot) {
        String tenantId = tenantContext.requireTenantId();
        authorizationService.requireStoreAccess(storeId);
        if (snapshot == null || snapshot.skuId() == null || snapshot.unitId() == null
            || snapshot.quantity() == null || snapshot.occurredAt() == null) {
            throw new ServiceException("CAT-WBC-031: 成交计量快照不完整", 409);
        }
        WeightedBarcodeTemplateView view = requireTemplate(tenantId, snapshot.templateId());
        if ("DRAFT".equals(view.state()) || view.versionNo() != snapshot.templateVersion()
            || !java.util.Objects.equals(view.contentSha256(), snapshot.templateSha256())
            || "STORE".equals(view.scopeType()) && !java.util.Objects.equals(view.storeId(), storeId)) {
            throw new ServiceException("CAT-WBC-032: 成交计量模板身份或适用范围不匹配", 409);
        }
        WeightedSkuView sku = mapper.findWeightedSkuIdentityByCode(tenantId, snapshot.skuCode());
        if (sku == null || !sku.skuId().equals(snapshot.skuId()) || !sku.unitId().equals(snapshot.unitId())) {
            throw new ServiceException("CAT-WBC-033: 成交计量商品或单位不匹配", 409);
        }
        ParsedMeasurement actual = WeightedBarcodeRules.parse(toDomain(view), snapshot.rawBarcode(),
            snapshot.unitPriceMinor(), sku.decimalScale(), snapshot.occurredAt());
        if (!actual.encodedValue().equals(snapshot.encodedValue())
            || actual.quantity().compareTo(snapshot.quantity()) != 0
            || actual.amountMinor() != snapshot.amountMinor() || !actual.currency().equals(snapshot.currency())
            || !actual.parseSha256().equals(snapshot.parseSha256())
            || actual.roundingApplied() != snapshot.roundingApplied()) {
            throw new ServiceException("CAT-WBC-034: 成交计量快照验算不一致", 409);
        }
    }

    private WeightedBarcodeTemplateView selectOne(List<WeightedBarcodeTemplateView> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new ServiceException("CAT-WBC-026: 无适用的已发布秤码模板", 404);
        }
        WeightedBarcodeTemplateView first = candidates.get(0);
        if (candidates.size() > 1) {
            WeightedBarcodeTemplateView second = candidates.get(1);
            boolean sameRank = first.scopeType().equals(second.scopeType())
                && first.prefixValue().length() == second.prefixValue().length()
                && first.priorityNo().equals(second.priorityNo());
            if (sameRank) {
                throw new ServiceException("CAT-WBC-027: 模板选择存在歧义", 409);
            }
        }
        return first;
    }

    private void appendHistory(String tenantId, WeightedBarcodeTemplateView view, String eventType,
                               String hash, LocalDateTime occurredAt) {
        mapper.insertWeightedBarcodeHistory(tenantId, IdWorker.getId(), view.templateId(), eventType,
            view.version(), hash, json(view), occurredAt);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("CAT-WBC-030: 模板事件序列化失败", 500);
        }
    }

    private WeightedBarcodeTemplateView requireTemplate(String tenantId, Long templateId) {
        WeightedBarcodeTemplateView result = mapper.findWeightedBarcodeTemplate(tenantId, templateId);
        if (result == null) {
            throw new ServiceException("CAT-WBC-028: 模板不存在或不可见", 404);
        }
        return result;
    }

    private Template toDomain(WeightedBarcodeTemplateView view) {
        return new Template(view.templateId(), view.templateCode(), view.versionNo(), view.scopeType(), view.storeId(),
            view.barcodeKind(), view.symbology(), view.prefixValue(), view.totalLength(), view.skuStartPos(),
            view.skuLength(), view.valueStartPos(), view.valueLength(), view.valueScale(), view.priorityNo(),
            view.effectiveFrom(), view.effectiveTo(), view.contentSha256());
    }

    private String requireScope(String value, Long storeId) {
        String scope = normalize(value);
        if (!("TENANT".equals(scope) && storeId == null) && !("STORE".equals(scope) && storeId != null)) {
            throw new ServiceException("CAT-WBC-029: 模板范围形状无效", 400);
        }
        return scope;
    }

    private void requireStoreAccess(Long storeId) {
        if (storeId != null) {
            authorizationService.requireStoreAccess(storeId);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    /** 创建模板的应用命令；tenant_id 永远不由命令携带。 */
    public record CreateTemplate(String templateCode, int versionNo, String scopeType, Long storeId,
                                 String barcodeKind, String prefixValue, int skuStartPos, int skuLength,
                                 int valueStartPos, int valueLength, int valueScale, int priorityNo,
                                 Instant effectiveFrom, Instant effectiveTo) {
    }
}
