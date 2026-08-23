package com.jingshanghui.pos.migration.infrastructure.security;

import com.jingshanghui.pos.migration.application.port.MigrationStagingCipher.SealedValue;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Constructor;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmMigrationStagingCipherTest {
    @Test
    void productionConstructorIsExplicitlySelectedForSpringInjection() {
        Constructor<?>[] constructors = AesGcmMigrationStagingCipher.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(2);
        assertThat(Arrays.stream(constructors)
            .filter(constructor -> constructor.isAnnotationPresent(Autowired.class)))
            .singleElement()
            .satisfies(constructor -> {
                assertThat(constructor.getParameterTypes()).containsExactly(String.class, String.class);
                assertThat(constructor.canAccess(null)).isTrue();
            });

        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 13);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("migration-test", Map.of(
                "jshpos.migration.staging-key-base64", Base64.getEncoder().encodeToString(key),
                "jshpos.migration.staging-key-version", "context-v1")));
            context.register(AesGcmMigrationStagingCipher.class);
            context.refresh();
            AesGcmMigrationStagingCipher cipher = context.getBean(AesGcmMigrationStagingCipher.class);
            SealedValue sealed = cipher.seal("TENANT_A:BATCH:ROW", "payload");
            assertThat(cipher.open("TENANT_A:BATCH:ROW", sealed)).isEqualTo("payload");
        }
    }

    @Test
    void sealsWithAadAndDetectsTamperingOrCrossTenantReplacement() {
        byte[] key=new byte[32];Arrays.fill(key,(byte)7);
        AesGcmMigrationStagingCipher cipher=new AesGcmMigrationStagingCipher(key,"kms-v1",new SecureRandom());
        SealedValue sealed=cipher.seal("TENANT_A:BATCH:ROW:MEMBER","{\"identity\":\"secret\"}");
        assertThat(sealed.cipherText()).doesNotContain("secret");
        assertThat(sealed.contentHmac()).matches("^[a-f0-9]{64}$");
        assertThat(cipher.open("TENANT_A:BATCH:ROW:MEMBER",sealed)).contains("secret");
        assertThatThrownBy(() -> cipher.open("TENANT_B:BATCH:ROW:MEMBER",sealed))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-SECURITY-003");
        SealedValue tampered=new SealedValue(sealed.cipherText()+"A",sealed.keyVersion(),sealed.contentHmac());
        assertThatThrownBy(() -> cipher.open("TENANT_A:BATCH:ROW:MEMBER",tampered))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void missingOrWrongKeyFailsClosed() {
        AesGcmMigrationStagingCipher missing=new AesGcmMigrationStagingCipher(new byte[0],"",new SecureRandom());
        assertThatThrownBy(() -> missing.seal("aad","value")).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DMT-SECURITY-001");
        AesGcmMigrationStagingCipher shortKey=new AesGcmMigrationStagingCipher(new byte[16],"v1",new SecureRandom());
        assertThatThrownBy(() -> shortKey.seal("aad","value")).isInstanceOf(ServiceException.class);
        AesGcmMigrationStagingCipher nullConfiguration = new AesGcmMigrationStagingCipher(null, null,
            new SecureRandom());
        assertThatThrownBy(() -> nullConfiguration.seal("aad", "value")).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DMT-SECURITY-001");
    }

    @Test
    void publicConstructorAcceptsOnlyValidBase64Aes256KeyAndVersion() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 11);
        AesGcmMigrationStagingCipher cipher = new AesGcmMigrationStagingCipher(
            Base64.getEncoder().encodeToString(key), " kms-v2 ");
        SealedValue sealed = cipher.seal("TENANT_A:BATCH:ROW", "payload");
        assertThat(sealed.keyVersion()).isEqualTo("kms-v2");
        assertThat(cipher.open("TENANT_A:BATCH:ROW", sealed)).isEqualTo("payload");

        for (String encoded : new String[]{null, "", "not-base64"}) {
            AesGcmMigrationStagingCipher invalid = new AesGcmMigrationStagingCipher(encoded, "v1");
            assertThatThrownBy(() -> invalid.seal("aad", "value"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-SECURITY-001");
        }
        AesGcmMigrationStagingCipher blankVersion = new AesGcmMigrationStagingCipher(
            Base64.getEncoder().encodeToString(key), " ");
        assertThatThrownBy(() -> blankVersion.seal("aad", "value"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-SECURITY-001");
    }

    @Test
    void rejectsMissingAadWrongVersionMissingHmacAndMalformedCiphertext() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 5);
        AesGcmMigrationStagingCipher cipher = new AesGcmMigrationStagingCipher(key, "v1", new SecureRandom());
        for (String aad : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> cipher.seal(aad, "value"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-SECURITY-001");
        }
        SealedValue sealed = cipher.seal("aad", "value");
        assertThatThrownBy(() -> cipher.open("aad", null)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DMT-SECURITY-003");
        assertThatThrownBy(() -> cipher.open("aad", new SealedValue(sealed.cipherText(), "v2",
            sealed.contentHmac()))).isInstanceOf(ServiceException.class).hasMessageContaining("DMT-SECURITY-003");
        assertThatThrownBy(() -> cipher.open("aad", new SealedValue(sealed.cipherText(), "v1", null)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-SECURITY-003");

        assertMalformed(cipher, key, "aad", "not-packed");
        assertMalformed(cipher, key, "aad", "bad.bad");
        String oneByteNonce = Base64.getEncoder().encodeToString(new byte[]{1});
        String ciphertext = Base64.getEncoder().encodeToString(new byte[]{2});
        assertMalformed(cipher, key, "aad", oneByteNonce + "." + ciphertext);
    }

    private void assertMalformed(AesGcmMigrationStagingCipher cipher, byte[] key, String aad, String packed) {
        SealedValue forged = new SealedValue(packed, "v1", contentHmac(key, aad, packed));
        assertThatThrownBy(() -> cipher.open(aad, forged)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DMT-SECURITY-004");
    }

    private String contentHmac(byte[] key, String aad, String packed) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((aad + "\n" + packed)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
