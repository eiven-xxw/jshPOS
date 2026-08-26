package com.jingshanghui.pos.reporting.infrastructure.security;

import com.jingshanghui.pos.reporting.application.port.ReportingBatchReadPort.SalesKey;
import com.jingshanghui.pos.reporting.application.port.SalesPageCursorCodec.CursorEnvelope;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 销售 keyset 游标的租户、筛选、投影版本和签名防篡改回归。 */
class HmacSalesPageCursorCodecTest {
    private static final byte[] KEY = "sales-page-cursor-key-32-bytes!!".getBytes(StandardCharsets.UTF_8);
    private final HmacSalesPageCursorCodec codec =
        new HmacSalesPageCursorCodec(KEY);

    @Test void roundTripsOnlyInsideFrozenTenantFilterAndProjection() {
        CursorEnvelope expected=new CursorEnvelope("tenant_alpha","a".repeat(64),"g5d-v1",
            new SalesKey(LocalDate.of(2026,8,17),11L,"T1",7L,"CNY"));
        String token=codec.encode(expected);
        assertThat(codec.decodeAndVerify(token,"tenant_alpha","a".repeat(64),"g5d-v1"))
            .isEqualTo(expected);
        assertThatThrownBy(() -> codec.decodeAndVerify(token,"tenant_beta","a".repeat(64),"g5d-v1"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-008");
        assertThatThrownBy(() -> codec.decodeAndVerify(token,"tenant_alpha","b".repeat(64),"g5d-v1"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-008");
        assertThatThrownBy(() -> codec.decodeAndVerify(token,"tenant_alpha","a".repeat(64),"g5d-v2"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-008");
    }

    @Test void rejectsSignatureTampering() {
        String token=codec.encode(new CursorEnvelope("tenant_alpha","a".repeat(64),"g5d-v1",null));
        String tampered=token.substring(0,token.length()-1)+(token.endsWith("A")?"B":"A");
        assertThatThrownBy(() -> codec.decodeAndVerify(tampered,"tenant_alpha","a".repeat(64),null))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-008");
    }

    @Test void rejectsMalformedTokenUnsupportedFormatAndTruncatedPayload() throws Exception {
        assertThatThrownBy(() -> codec.decodeAndVerify(null,"tenant_alpha","a".repeat(64),null))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-008");
        assertThatThrownBy(() -> codec.decodeAndVerify("not-a-two-part-token","tenant_alpha","a".repeat(64),null))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-008");

        assertThatThrownBy(() -> codec.decodeAndVerify(signedPayload(intPayload(2)),
            "tenant_alpha","a".repeat(64),null))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-008");

        ByteArrayOutputStream truncated = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(truncated)) {
            output.writeInt(1);
            output.writeShort(10);
            output.writeByte('x');
        }
        assertThatThrownBy(() -> codec.decodeAndVerify(signedPayload(truncated.toByteArray()),
            "tenant_alpha","a".repeat(64),null))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-008");
    }

    @Test void rejectsWeakKeyAndOversizedCursorField() {
        assertThatThrownBy(() -> new HmacSalesPageCursorCodec(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HmacSalesPageCursorCodec(new byte[31]))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(new CursorEnvelope("x".repeat(513),"a".repeat(64),
            "g5d-v1",null)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-008");
    }

    private byte[] intPayload(int value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(value);
        }
        return bytes.toByteArray();
    }

    private String signedPayload(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY,"HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload));
    }
}
