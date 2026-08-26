package com.jingshanghui.pos.reporting.infrastructure.security;

import com.jingshanghui.pos.reporting.application.port.ReportingBatchReadPort.SalesKey;
import com.jingshanghui.pos.reporting.application.port.SalesPageCursorCodec;
import org.dromara.common.core.exception.ServiceException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;

/** HMAC-SHA256 销售游标实现；任何租户、筛选、版本或排序键篡改均失败关闭。 */
public final class HmacSalesPageCursorCodec implements SalesPageCursorCodec {
    private static final String HMAC = "HmacSHA256";
    private static final int FORMAT_VERSION = 1;
    private final byte[] key;

    public HmacSalesPageCursorCodec(byte[] key) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("销售游标签名密钥至少 32 字节");
        }
        this.key = key.clone();
    }

    @Override
    public String encode(CursorEnvelope envelope) {
        byte[] payload = serialize(envelope);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    @Override
    public CursorEnvelope decodeAndVerify(String token, String expectedTenantId, String expectedFilterSha256,
                                          String expectedProjectionVersion) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 2) throw invalid();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payload), signature)) throw invalid();
            CursorEnvelope envelope = deserialize(payload);
            if (!expectedTenantId.equals(envelope.tenantId())
                || !expectedFilterSha256.equals(envelope.filterSha256())
                || expectedProjectionVersion != null && !expectedProjectionVersion.equals(envelope.projectionVersion())) {
                throw invalid();
            }
            return envelope;
        } catch (ServiceException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw invalid();
        }
    }

    private byte[] serialize(CursorEnvelope envelope) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(FORMAT_VERSION);
                writeText(output, envelope.tenantId());
                writeText(output, envelope.filterSha256());
                writeText(output, envelope.projectionVersion());
                output.writeBoolean(envelope.after() != null);
                if (envelope.after() != null) {
                    output.writeLong(envelope.after().businessDate().toEpochDay());
                    output.writeLong(envelope.after().storeId());
                    writeText(output, envelope.after().terminalId());
                    output.writeLong(envelope.after().cashierId());
                    writeText(output, envelope.after().currency());
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("销售游标序列化失败", exception);
        }
    }

    private CursorEnvelope deserialize(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != FORMAT_VERSION) throw invalid();
            String tenantId = readText(input);
            String filterSha256 = readText(input);
            String projectionVersion = readText(input);
            SalesKey after = null;
            if (input.readBoolean()) {
                after = new SalesKey(LocalDate.ofEpochDay(input.readLong()), input.readLong(), readText(input),
                    input.readLong(), readText(input));
            }
            if (input.available() != 0) throw invalid();
            return new CursorEnvelope(tenantId, filterSha256, projectionVersion, after);
        }
    }

    private void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512) throw invalid();
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private String readText(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > 512) throw invalid();
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw invalid();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("销售游标签名失败", exception);
        }
    }

    private ServiceException invalid() {
        return new ServiceException("RPT-R2R2-008: 销售分页游标无效、被篡改或已不适用", 400);
    }
}
