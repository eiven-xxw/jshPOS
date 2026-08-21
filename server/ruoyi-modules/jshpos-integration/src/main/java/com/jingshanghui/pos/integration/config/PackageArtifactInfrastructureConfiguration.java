package com.jingshanghui.pos.integration.config;

import com.jingshanghui.pos.catalog.application.packagev1.PackageObjectPort;
import com.jingshanghui.pos.catalog.application.packagev1.PackageSigningPort;
import com.jingshanghui.pos.integration.infrastructure.artifact.LocalPackageArtifactStore;
import com.jingshanghui.pos.integration.infrastructure.artifact.SoftwareEd25519PackageSigner;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Provider 无关包存储装配；未显式配置时 Package 服务继续失败关闭。 */
@Configuration(proxyBeanMethods = false)
public class PackageArtifactInfrastructureConfiguration {

    @Bean
    @ConditionalOnProperty(name = "jshpos.package-artifacts.local-root")
    LocalPackageArtifactStore localPackageArtifactStore(
        @Value("${jshpos.package-artifacts.local-root}") String root) {
        return new LocalPackageArtifactStore(Path.of(root));
    }

    @Bean
    @ConditionalOnProperty(name = "jshpos.package-artifacts.local-root")
    PackageObjectPort catalogPackageObjectPort(LocalPackageArtifactStore store) {
        return new PackageObjectPort() {
            @Override public void put(String key, byte[] payload, byte[] signature) {
                store.put(key, payload, signature);
            }

            @Override public StoredObject get(String key) {
                LocalPackageArtifactStore.Stored value = store.get(key);
                return value == null ? null : new StoredObject(value.payload(), value.signature());
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = "jshpos.package-artifacts.local-root")
    PromotionPackagePorts.ObjectPort promotionPackageObjectPort(LocalPackageArtifactStore store) {
        return new PromotionPackagePorts.ObjectPort() {
            @Override public void put(String key, byte[] payload, byte[] signature) {
                store.put(key, payload, signature);
            }

            @Override public PromotionPackagePorts.StoredObject get(String key) {
                LocalPackageArtifactStore.Stored value = store.get(key);
                return value == null ? null
                    : new PromotionPackagePorts.StoredObject(value.payload(), value.signature());
            }
        };
    }

    @Bean
    @ConditionalOnProperty(name = {
        "jshpos.package-artifacts.software-signing-key-id",
        "jshpos.package-artifacts.software-signing-pkcs8-base64"
    })
    SoftwareEd25519PackageSigner softwareEd25519PackageSigner(
        @Value("${jshpos.package-artifacts.software-signing-key-id}") String keyId,
        @Value("${jshpos.package-artifacts.software-signing-pkcs8-base64}") String privateKey) {
        return new SoftwareEd25519PackageSigner(keyId, privateKey);
    }

    @Bean
    @ConditionalOnProperty(name = {
        "jshpos.package-artifacts.software-signing-key-id",
        "jshpos.package-artifacts.software-signing-pkcs8-base64"
    })
    PackageSigningPort catalogPackageSigningPort(SoftwareEd25519PackageSigner signer) {
        return (tenantId, payload) -> new PackageSigningPort.SigningResult(
            signer.keyId(), "Ed25519", signer.sign(payload));
    }

    @Bean
    @ConditionalOnProperty(name = {
        "jshpos.package-artifacts.software-signing-key-id",
        "jshpos.package-artifacts.software-signing-pkcs8-base64"
    })
    PromotionPackagePorts.SigningPort promotionPackageSigningPort(SoftwareEd25519PackageSigner signer) {
        return (tenantId, payload) -> new PromotionPackagePorts.SigningResult(
            signer.keyId(), signer.sign(payload));
    }
}
