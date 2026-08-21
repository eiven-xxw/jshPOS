package com.jingshanghui.pos.integration.application;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommercialV1AssemblyVerifierTest {
    @Test
    void allOwnerCapabilitiesAreUniqueAndExternalBoundariesRemainUnavailable() {
        DefaultListableBeanFactory factory = completeFactory();
        CommercialV1AssemblyVerifier verifier =
            new CommercialV1AssemblyVerifier(factory, new ExternalBoundaryRegistry());

        CommercialV1AssemblyVerifier.AssemblySnapshot snapshot = verifier.verifyNow();

        assertThat(snapshot.ownerBeans()).hasSize(16);
        assertThat(snapshot.externalBoundaries()).hasSize(5);
        assertThat(snapshot.externalBoundaries().values())
            .allSatisfy(state -> assertThat(state.runtimeStatus()).isEqualTo("UNAVAILABLE"));
    }

    @Test
    void missingOwnerCapabilityFailsClosed() {
        DefaultListableBeanFactory factory = completeFactory();
        factory.removeBeanDefinition("owner-release");
        CommercialV1AssemblyVerifier verifier =
            new CommercialV1AssemblyVerifier(factory, new ExternalBoundaryRegistry());

        assertThatThrownBy(verifier::verifyNow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CORE-ASSEMBLY-001")
            .hasMessageContaining("release expected=1 actual=0");
    }

    @Test
    void duplicateOwnerCapabilityFailsClosed() {
        DefaultListableBeanFactory factory = completeFactory();
        Class<?> orderType = CommercialV1AssemblyContract.requiredCapabilities().stream()
            .filter(capability -> capability.owner().equals("order"))
            .findFirst().orElseThrow().beanType();
        factory.registerBeanDefinition("owner-order-duplicate", new RootBeanDefinition(orderType));
        CommercialV1AssemblyVerifier verifier =
            new CommercialV1AssemblyVerifier(factory, new ExternalBoundaryRegistry());

        assertThatThrownBy(verifier::verifyNow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("order expected=1 actual=2");
    }

    @Test
    void lifecycleStoresImmutableAssemblySnapshot() {
        CommercialV1AssemblyVerifier verifier =
            new CommercialV1AssemblyVerifier(completeFactory(), new ExternalBoundaryRegistry());

        assertThatThrownBy(verifier::snapshot)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CORE-ASSEMBLY-002");

        verifier.afterSingletonsInstantiated();
        assertThat(verifier.snapshot().ownerBeans()).hasSize(16);
        assertThatThrownBy(() -> verifier.snapshot().ownerBeans().put("rogue", "rogueBean"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private static DefaultListableBeanFactory completeFactory() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        CommercialV1AssemblyContract.requiredCapabilities().forEach(capability ->
            factory.registerBeanDefinition("owner-" + capability.owner(),
                new RootBeanDefinition(capability.beanType())));
        return factory;
    }
}
