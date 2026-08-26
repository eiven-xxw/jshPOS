package com.jingshanghui.pos.reporting.infrastructure.security;

import com.jingshanghui.pos.reporting.application.port.InventoryCostPageCursorCodec.CursorEnvelope;
import com.jingshanghui.pos.reporting.application.port.ReportingBatchReadPort.InventoryCostKey;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 库存成本 keyset 游标的租户、筛选、投影版本和签名防篡改回归。 */
class HmacInventoryCostPageCursorCodecTest {
    private static final byte[] KEY = "inventory-page-cursor-key-32-bytes".getBytes(StandardCharsets.UTF_8);
    private final HmacInventoryCostPageCursorCodec codec = new HmacInventoryCostPageCursorCodec(KEY);

    @Test void roundTripsOnlyInsideFrozenTenantFilterAndProjection() {
        CursorEnvelope expected=new CursorEnvelope("tenant_alpha","a".repeat(64),"g5d-v1",
            new InventoryCostKey(LocalDate.of(2026,8,17),11L,"W1",101L,"CNY"));
        String token=codec.encode(expected);
        assertThat(codec.decodeAndVerify(token,"tenant_alpha","a".repeat(64),"g5d-v1"))
            .isEqualTo(expected);
        assertThatThrownBy(() -> codec.decodeAndVerify(token,"tenant_beta","a".repeat(64),"g5d-v1"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-022");
        assertThatThrownBy(() -> codec.decodeAndVerify(token,"tenant_alpha","b".repeat(64),"g5d-v1"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-022");
        assertThatThrownBy(() -> codec.decodeAndVerify(token,"tenant_alpha","a".repeat(64),"g5d-v2"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-022");
    }

    @Test void rejectsTamperingMalformedTokenAndWeakKey() {
        String token=codec.encode(new CursorEnvelope("tenant_alpha","a".repeat(64),"g5d-v1",null));
        String tampered=token.substring(0,token.length()-1)+(token.endsWith("A")?"B":"A");
        assertThatThrownBy(() -> codec.decodeAndVerify(tampered,"tenant_alpha","a".repeat(64),null))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-022");
        assertThatThrownBy(() -> codec.decodeAndVerify("bad","tenant_alpha","a".repeat(64),null))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-022");
        assertThatThrownBy(() -> new HmacInventoryCostPageCursorCodec(new byte[31]))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
