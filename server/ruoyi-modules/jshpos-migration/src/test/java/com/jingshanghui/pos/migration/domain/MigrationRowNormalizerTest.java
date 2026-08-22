package com.jingshanghui.pos.migration.domain;

import com.jingshanghui.pos.migration.domain.MigrationStates.DataType;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationRowNormalizerTest {
    private MigrationRowNormalizer normalizer;

    @BeforeEach void setUp() {
        normalizer = new MigrationRowNormalizer(new UlidGenerator(
            Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC)));
    }

    @Test
    void freezesAllFourOwnerRowShapesWithoutTenantField() {
        var catalog = normalizer.normalize(DataType.CATALOG, catalogHeaders(), List.of(catalogRow("SKU-1", "690001")));
        var supplier = normalizer.normalize(DataType.SUPPLIER, List.of("supplierCode","supplierName"),
            List.of(Map.of("supplierCode","SUP-1","supplierName","测试供应商")));
        var opening = normalizer.normalize(DataType.OPENING_INVENTORY,
            List.of("storeId","warehouseId","skuCode","quantity","unitCostMinor","businessDate"),
            List.of(Map.of("storeId","101","warehouseId","01K2A000000000000000000010","skuCode","SKU-1",
                "quantity","10.500000","unitCostMinor","123.450000","businessDate","2026-08-22")));
        var member = normalizer.normalize(DataType.MEMBER, List.of("identityType","identityValue"),
            List.of(Map.of("identityType","MOBILE","identityValue","13800000000")));

        assertThat(catalog.get(0).canonicalJson()).contains("\"skuCode\":\"SKU-1\"").doesNotContain("tenant");
        assertThat(supplier.get(0).canonicalJson()).contains("supplierId");
        assertThat(opening.get(0).canonicalJson()).contains("\"currency\":\"CNY\"");
        assertThat(member.get(0).canonicalJson()).contains("identityValue");
        assertThat(List.of(catalog,supplier,opening,member)).allSatisfy(rows ->
            assertThat(rows.get(0).rowSha256()).matches("^[a-f0-9]{64}$"));
    }

    @Test
    void rejectsUnknownHeadersDuplicatesAndInvalidBusinessValues() {
        assertThatThrownBy(() -> normalizer.normalize(DataType.MEMBER,
            List.of("identityType","identityValue","tenantId"), List.of(Map.of())))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-MAPPING-003");
        assertThatThrownBy(() -> normalizer.normalize(DataType.CATALOG, catalogHeaders(),
            List.of(catalogRow("SKU-1","690001"), catalogRow("SKU-1","690002"))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("SKU 编码重复");
        Map<String,String> negative = new LinkedHashMap<>(Map.of("storeId","101","warehouseId",
            "01K2A000000000000000000010","skuCode","SKU-1","quantity","-1","unitCostMinor","1",
            "businessDate","2026-08-22"));
        assertThatThrownBy(() -> normalizer.normalize(DataType.OPENING_INVENTORY,
            List.copyOf(negative.keySet()), List.of(negative))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> normalizer.normalize(DataType.MEMBER,List.of("identityType","identityValue"),
            List.of(Map.of("identityType","UNKNOWN","identityValue","x"))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("身份类型无效");
        Map<String,String> nonBaseUnit = new LinkedHashMap<>(catalogRow("SKU-2", "690003"));
        nonBaseUnit.put("ratioNumerator", "12");
        assertThatThrownBy(() -> normalizer.normalize(DataType.CATALOG, catalogHeaders(), List.of(nonBaseUnit)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("1:1 基础单位");
    }

    @Test
    void acceptsStandardProductsAndPreservesLeadingZeroBarcodes() {
        Map<String,String> row = new LinkedHashMap<>(catalogRow("SKU-STD", "00001234"));
        row.put("productType", "STANDARD");
        var result = normalizer.normalize(DataType.CATALOG, catalogHeaders(), List.of(row));
        assertThat(result.get(0).canonicalJson()).contains("\"productType\":\"STANDARD\"")
            .contains("00001234");
    }

    @Test
    void returnsAllRowErrorsWithoutEchoingRawMemberValues() {
        var result = normalizer.preflight(DataType.MEMBER, List.of("identityType", "identityValue"), List.of(
            Map.of("identityType", "BAD-A", "identityValue", "secret-a@example.test"),
            Map.of("identityType", "BAD-B", "identityValue", "secret-b@example.test")));
        assertThat(result.rows()).isEmpty();
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).allSatisfy(error -> {
            assertThat(error.errorCode()).isEqualTo("DMT-PREFLIGHT-041");
            assertThat(error.maskedMessage()).doesNotContain("secret-");
        });
    }

    @Test
    void rejectsCatalogBrandTypeRatioBarcodeAndNumberBoundaries() {
        Map<String,String> brandMismatch = new LinkedHashMap<>(catalogRow("SKU-BRAND", "690010"));
        brandMismatch.put("brandCode", "BRAND");
        assertThatThrownBy(() -> normalizer.normalize(DataType.CATALOG, catalogHeaders(), List.of(brandMismatch)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("品牌编码与名称");
        Map<String,String> type = new LinkedHashMap<>(catalogRow("SKU-TYPE", "690011"));
        type.put("productType", "SERVICE");
        assertThatThrownBy(() -> normalizer.normalize(DataType.CATALOG, catalogHeaders(), List.of(type)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("商品类型无效");
        Map<String,String> duplicateBarcodes = new LinkedHashMap<>(catalogRow("SKU-BAR", "690012|690012"));
        assertThatThrownBy(() -> normalizer.normalize(DataType.CATALOG, catalogHeaders(), List.of(duplicateBarcodes)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("条码重复");
        Map<String,String> badBarcode = new LinkedHashMap<>(catalogRow("SKU-BAD", "=690013"));
        assertThatThrownBy(() -> normalizer.normalize(DataType.CATALOG, catalogHeaders(), List.of(badBarcode)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("条码格式无效");
        Map<String,String> decimal = new LinkedHashMap<>(catalogRow("SKU-DEC", "690014"));
        decimal.put("decimalScale", "seven");
        assertThatThrownBy(() -> normalizer.normalize(DataType.CATALOG, catalogHeaders(), List.of(decimal)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不是整数");
        Map<String,String> ratio = new LinkedHashMap<>(catalogRow("SKU-RATIO", "690015"));
        ratio.put("ratioNumerator", "0");
        assertThatThrownBy(() -> normalizer.normalize(DataType.CATALOG, catalogHeaders(), List.of(ratio)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("必须为正整数");
    }

    @Test
    void rejectsOpeningAndIdentityDuplicatesAndMalformedDates() {
        List<String> openingHeaders = List.of("storeId","warehouseId","skuCode","quantity","unitCostMinor","businessDate");
        Map<String,String> opening = Map.of("storeId","101","warehouseId","01K2A000000000000000000010",
            "skuCode","SKU-1","quantity","1","unitCostMinor","1","businessDate","2026-08-22");
        assertThatThrownBy(() -> normalizer.normalize(DataType.OPENING_INVENTORY, openingHeaders,
            List.of(opening, opening))).isInstanceOf(ServiceException.class).hasMessageContaining("期初库存重复");
        Map<String,String> badDate = new LinkedHashMap<>(opening); badDate.put("businessDate", "2026-02-31");
        assertThatThrownBy(() -> normalizer.normalize(DataType.OPENING_INVENTORY, openingHeaders, List.of(badDate)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("业务日期无效");
        List<String> memberHeaders = List.of("identityType", "identityValue");
        Map<String,String> member = Map.of("identityType", "EMAIL", "identityValue", "same@example.test");
        assertThatThrownBy(() -> normalizer.normalize(DataType.MEMBER, memberHeaders, List.of(member, member)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("会员身份重复");
        assertThat(new MigrationRowNormalizer.PreflightResult(null, null).rows()).isEmpty();
        assertThat(new MigrationRowNormalizer.PreflightResult(null, null).errors()).isEmpty();
    }

    private List<String> catalogHeaders() {
        return List.of("spuCode","skuCode","name","categoryCode","categoryName","brandCode","brandName",
            "productType","unitCode","unitName","decimalScale","ratioNumerator","ratioDenominator","barcodes");
    }
    private Map<String,String> catalogRow(String sku,String barcode) {
        Map<String,String> row=new LinkedHashMap<>(); row.put("spuCode","SPU-1");row.put("skuCode",sku);
        row.put("name","测试商品");row.put("categoryCode","CAT-1");row.put("categoryName","测试分类");
        row.put("brandCode","");row.put("brandName","");row.put("productType","COUNT");row.put("unitCode","PCS");
        row.put("unitName","件");row.put("decimalScale","0");row.put("ratioNumerator","1");
        row.put("ratioDenominator","1");row.put("barcodes",barcode);return row;
    }
}
