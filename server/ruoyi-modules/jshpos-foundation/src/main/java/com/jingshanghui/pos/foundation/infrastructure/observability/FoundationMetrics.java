package com.jingshanghui.pos.foundation.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 仅使用固定低基数 action/result 标签，不记录租户、用户或对象 ID。
 */
@Component
public class FoundationMetrics {

    private final MeterRegistry registry;

    public FoundationMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registry = registryProvider.getIfAvailable();
    }

    public void increment(String action, String result) {
        if (registry != null) {
            registry.counter("jshpos.foundation.actions", "action", action, "result", result).increment();
        }
    }
}
