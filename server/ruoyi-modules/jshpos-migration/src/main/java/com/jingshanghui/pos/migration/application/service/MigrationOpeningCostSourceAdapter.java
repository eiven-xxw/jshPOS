package com.jingshanghui.pos.migration.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.BusinessMigrationCatalogPort;
import com.jingshanghui.pos.catalog.application.port.BusinessMigrationCatalogPort.ProductMigrationResult;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.inventory.application.port.OpeningInventoryCostSourcePort;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort;
import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.StagingRecord;
import com.jingshanghui.pos.migration.application.port.MigrationStagingCipher;
import com.jingshanghui.pos.migration.application.port.MigrationStagingCipher.SealedValue;
import com.jingshanghui.pos.migration.domain.MigrationRules;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** Costing Owner 查询期初成本的只读适配器；只解密指定可信租户的单行 staging。 */
@Component
@RequiredArgsConstructor
public class MigrationOpeningCostSourceAdapter implements OpeningInventoryCostSourcePort {
    private final TrustedTenantContext tenantContext;
    private final BusinessMigrationPersistencePort persistence;
    private final MigrationStagingCipher cipher;
    private final BusinessMigrationCatalogPort catalog;
    private final ObjectMapper objectMapper;

    @Override
    public OpeningCostSource requireOpeningLine(String sourceLineId) {
        StagingRecord row = persistence.findStagingRow(tenantContext.requireTenantId(), sourceLineId);
        if (row == null || !"OPENING_INVENTORY".equals(row.dataType()) || !"READY".equals(row.state())) {
            throw new ServiceException("DMT-COST-SOURCE-001: 期初库存成本来源不存在或已清理", 409);
        }
        Map<String, Object> value = read(row);
        ProductMigrationResult product = catalog.requireProduct(row.batchId(), string(value, "skuCode"));
        return new OpeningCostSource(row.batchId(), row.rowId(), product.skuId(), product.baseUnitId(),
            new BigDecimal(string(value, "quantity")), new BigDecimal(string(value, "unitCostMinor")), "CNY");
    }

    private Map<String, Object> read(StagingRecord row) {
        try {
            String json = cipher.open(aad(row), new SealedValue(row.cipherText(), row.keyVersion(), row.contentHmac()));
            if (!MigrationRules.digest(json).equals(row.rowSha256())) {
                throw new ServiceException("DMT-SECURITY-005: staging 规范摘要漂移", 409);
            }
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new ServiceException("DMT-SECURITY-006: staging 规范 JSON 损坏", 409);
        }
    }

    private String aad(StagingRecord row) {
        return tenantContext.requireTenantId() + ":" + row.batchId() + ":" + row.rowId() + ":" + row.dataType();
    }
    private String string(Map<String, Object> value, String key) { return String.valueOf(value.get(key)); }
}
