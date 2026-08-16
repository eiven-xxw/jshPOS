package com.jingshanghui.pos.catalog.application.packagev1;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogPackageCodecTest {

    private static final Instant AT = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void canonicalizesSortsEscapesHashesAndProtectsPayload() {
        assertThat(CatalogPackageCodec.supportsSchema("1.0")).isTrue();
        assertThat(CatalogPackageCodec.supportsSchema("0.9")).isTrue();
        assertThat(CatalogPackageCodec.supportsSchema("0.8")).isFalse();
        assertThat(CatalogPackageCodec.supportsSchema(null)).isFalse();
        CatalogPackageCodec.EncodedPackage encoded = CatalogPackageCodec.encode("tenant-a", 11L, 2, 1, AT,
            List.of(new CatalogPackageCodec.Record("PRICE", "2", "a|b\n"),
                new CatalogPackageCodec.Record("PRODUCT", "1", "x\\y")));
        String text = new String(encoded.payload(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("JSHCAT|1.0|tenant-a|11|2|1|2026-08-16T00:00:00Z\n");
        assertThat(text.indexOf("PRICE")).isLessThan(text.indexOf("PRODUCT"));
        assertThat(text).contains("a\\pb\\n", "x\\\\y");
        assertThat(encoded.sha256()).matches("[a-f0-9]{64}");
        assertThat(encoded.recordCount()).isEqualTo(2);
        byte[] copy = encoded.payload();
        copy[0] = 0;
        assertThat(encoded.payload()[0]).isNotZero();
    }

    @Test
    void verifiesEd25519AndRejectsCorruptionWrongHashOrMissingInputs() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        CatalogPackageCodec.EncodedPackage encoded = CatalogPackageCodec.encode("tenant-a", 11L, 1, 0, AT,
            List.of(new CatalogPackageCodec.Record("PRODUCT", "1", "payload")));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(encoded.payload());
        byte[] signature = signer.sign();
        assertThat(CatalogPackageCodec.verify(encoded, encoded.sha256(), signature, pair.getPublic())).isTrue();
        assertThat(CatalogPackageCodec.verify(encoded, "0".repeat(64), signature, pair.getPublic())).isFalse();
        byte[] damagedPayload = encoded.payload();
        damagedPayload[damagedPayload.length - 1] ^= 1;
        CatalogPackageCodec.EncodedPackage damaged = new CatalogPackageCodec.EncodedPackage(
            damagedPayload, encoded.sha256(), encoded.recordCount());
        assertThat(CatalogPackageCodec.verify(damaged, encoded.sha256(), signature, pair.getPublic())).isFalse();
        signature[0] ^= 1;
        assertThat(CatalogPackageCodec.verify(encoded, encoded.sha256(), signature, pair.getPublic())).isFalse();
        assertThat(CatalogPackageCodec.verify(null, encoded.sha256(), signature, pair.getPublic())).isFalse();
        assertThat(CatalogPackageCodec.verify(encoded, null, signature, pair.getPublic())).isFalse();
        assertThat(CatalogPackageCodec.verify(encoded, encoded.sha256(), null, pair.getPublic())).isFalse();
        assertThat(CatalogPackageCodec.verify(encoded, encoded.sha256(), signature, null)).isFalse();
    }

    @Test
    void rejectsInvalidManifestAndRecordsAndSigningAlgorithm() {
        assertBad(() -> CatalogPackageCodec.encode(null, 1L, 1, 0, AT, List.of()));
        assertBad(() -> CatalogPackageCodec.encode("t", 0L, 1, 0, AT, List.of()));
        assertBad(() -> CatalogPackageCodec.encode("t", 1L, 0, 0, AT, List.of()));
        assertBad(() -> CatalogPackageCodec.encode("t", 1L, 1, -1, AT, List.of()));
        assertBad(() -> CatalogPackageCodec.encode("t", 1L, 1, 1, AT, List.of()));
        assertBad(() -> CatalogPackageCodec.encode("t", 1L, 1, 0, null, List.of()));
        assertBad(() -> new CatalogPackageCodec.Record("", "1", "x"));
        assertBad(() -> new CatalogPackageCodec.Record("X", "", "x"));
        assertBad(() -> CatalogPackageCodec.encode("t", 1L, 1, 0, AT,
            List.of(new CatalogPackageCodec.Record("X", "1", null))));
        PackageSigningPort.SigningResult result = new PackageSigningPort.SigningResult("key", "Ed25519", new byte[]{1});
        byte[] copy = result.signature();
        copy[0] = 2;
        assertThat(result.signature()[0]).isEqualTo((byte) 1);
        assertThatThrownBy(() -> new PackageSigningPort.SigningResult("key", "RSA", new byte[]{1}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertBad(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ServiceException.class);
    }
}
