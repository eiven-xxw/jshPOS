package com.jingshanghui.pos.foundation.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FoundationMetricsTest {

    @Test
    void recordsOnlyFixedActionAndResultTagsWhenRegistryExists() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        FoundationMetrics metrics = new FoundationMetrics(provider);

        metrics.increment("org.create", "success");

        assertThat(registry.get("jshpos.foundation.actions")
            .tags("action", "org.create", "result", "success").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void remainsSafeWhenMetricsRegistryIsNotConfigured() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        new FoundationMetrics(provider).increment("org.create", "success");
    }
}
