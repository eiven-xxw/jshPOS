package com.jingshanghui.pos.integration.infrastructure.artifact;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 本地包对象和显式软件签名器的失败关闭回归。 */
class PackageArtifactInfrastructureTest {

    @TempDir
    Path root;

    @Test
    void atomicallyStoresOneEnvelopeAndRejectsKeyTraversalOrContentCollision() {
        LocalPackageArtifactStore store = new LocalPackageArtifactStore(root);
        byte[] payload = "canonical".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = new byte[64];
        String key = "tenant/TENANT_A/packages/catalog-1";

        store.put(key, payload, signature);
        store.put(key, payload, signature);
        assertThat(store.get(key).payload()).isEqualTo(payload);
        assertThat(store.get(key).signature()).isEqualTo(signature);
        assertThat(store.get("tenant/TENANT_A/packages/missing")).isNull();
        assertThatThrownBy(() -> store.put(key, "changed".getBytes(), signature))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不同内容");
        assertThatThrownBy(() -> store.get("../escape"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("可信命名空间");
    }

    @Test
    void signsWithConfiguredPkcs8WithoutExposingPrivateMaterial() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String encoded = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        SoftwareEd25519PackageSigner signer = new SoftwareEd25519PackageSigner("internal-key-1", encoded);
        byte[] signature = signer.sign("canonical".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(signer.keyId()).isEqualTo("internal-key-1");
        assertThat(signature).hasSize(64);
        assertThatThrownBy(() -> new SoftwareEd25519PackageSigner("", encoded))
            .isInstanceOf(ServiceException.class);
    }
}
