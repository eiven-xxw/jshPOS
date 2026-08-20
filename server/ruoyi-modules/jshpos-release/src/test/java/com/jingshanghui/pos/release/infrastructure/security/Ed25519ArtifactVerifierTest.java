package com.jingshanghui.pos.release.infrastructure.security;

import com.jingshanghui.pos.release.domain.ReleaseModels.*;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** 合成Ed25519签名包固定验证；不读取仓库密钥或生产对象存储。 */
class Ed25519ArtifactVerifierTest {
    @Test void verifiesSyntheticPackageAndRejectsTamper() throws Exception {
        byte[] bytes = "synthetic-apk-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(pair.getPrivate()); signer.update(bytes);
        String encoded = Base64.getEncoder().encodeToString(signer.sign());
        Release release = release(encoded);
        Ed25519ArtifactVerifier verifier = new Ed25519ArtifactVerifier(key -> bytes, version -> pair.getPublic());
        ArtifactObservation result = verifier.verify(release);
        assertThat(result.signatureValid()).isTrue();
        assertThat(result.sha256()).hasSize(64);

        Ed25519ArtifactVerifier tampered = new Ed25519ArtifactVerifier(key -> "tampered".getBytes(), version -> pair.getPublic());
        assertThat(tampered.verify(release).signatureValid()).isFalse();
        Ed25519ArtifactVerifier invalid = new Ed25519ArtifactVerifier(key -> bytes, version -> pair.getPublic());
        assertThatThrownBy(() -> invalid.verify(release("not-base64"))).hasMessageContaining("失败关闭");
        Ed25519ArtifactVerifier empty = new Ed25519ArtifactVerifier(key -> new byte[0], version -> pair.getPublic());
        assertThatThrownBy(() -> empty.verify(release(encoded))).hasMessageContaining("为空");
        Ed25519ArtifactVerifier missingKey = new Ed25519ArtifactVerifier(key -> bytes, version -> { throw new IllegalStateException(); });
        assertThatThrownBy(() -> missingKey.verify(release(encoded))).hasMessageContaining("失败关闭");
    }

    private static Release release(String signature) {
        CompatibilityWindow window = new CompatibilityWindow("1","2","1","2","1","2","10","14",null);
        return new Release("01K6A000000000000000000001","TENANT_A",ArtifactType.APK,"1.0",Channel.INTERNAL,
            "releases/TENANT_A/app.apk","0".repeat(64),signature,"synthetic-v1","1".repeat(40),"2".repeat(64),
            "3".repeat(64),window,Set.of(101L),ReleaseState.DRAFT,0,Instant.EPOCH);
    }
}
