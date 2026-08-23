package com.jingshanghui.pos.integration.application;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommercialV1AssemblyContractTest {
    @Test
    void ownerNamesAndCapabilityTypesAreUnique() {
        var capabilities = CommercialV1AssemblyContract.requiredCapabilities();

        assertThat(capabilities).hasSize(32);
        assertThat(new HashSet<>(capabilities.stream().map(
            CommercialV1AssemblyContract.OwnerCapability::owner).toList())).hasSize(32);
        assertThat(new HashSet<>(capabilities.stream().map(
            CommercialV1AssemblyContract.OwnerCapability::beanType).toList())).hasSize(32);
    }

    @Test
    void incompleteCapabilityDefinitionIsRejected() {
        assertThatThrownBy(() -> new CommercialV1AssemblyContract.OwnerCapability(
            "", "description", String.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CORE-ASSEMBLY-000");
        assertThatThrownBy(() -> new CommercialV1AssemblyContract.OwnerCapability(
            "owner", "", String.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommercialV1AssemblyContract.OwnerCapability(
            "owner", "description", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
