package com.jingshanghui.pos.integration.application;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产启动期 Owner 装配守卫。
 *
 * <p>关键应用能力缺失或重复时直接阻断启动，避免得到“能打包但运行时半装配”的实例。</p>
 */
public final class CommercialV1AssemblyVerifier implements SmartInitializingSingleton {
    private final ConfigurableListableBeanFactory beanFactory;
    private final ExternalBoundaryRegistry externalBoundaries;
    private volatile AssemblySnapshot snapshot;

    public CommercialV1AssemblyVerifier(ConfigurableListableBeanFactory beanFactory,
                                        ExternalBoundaryRegistry externalBoundaries) {
        this.beanFactory = beanFactory;
        this.externalBoundaries = externalBoundaries;
    }

    @Override
    public void afterSingletonsInstantiated() {
        this.snapshot = verifyNow();
    }

    /** 执行唯一性校验；不触发延迟 Bean 提前实例化。 */
    public AssemblySnapshot verifyNow() {
        List<String> violations = new ArrayList<>();
        Map<String, String> assembled = new LinkedHashMap<>();
        for (CommercialV1AssemblyContract.OwnerCapability capability
            : CommercialV1AssemblyContract.requiredCapabilities()) {
            String[] names = beanFactory.getBeanNamesForType(capability.beanType(), false, false);
            if (names.length != 1) {
                violations.add(capability.owner() + " expected=1 actual=" + names.length);
            } else {
                assembled.put(capability.owner(), names[0]);
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("CORE-ASSEMBLY-001: 正式Owner装配不完整或不唯一: "
                + String.join(", ", violations));
        }
        return new AssemblySnapshot(Map.copyOf(assembled), externalBoundaries.snapshot());
    }

    public AssemblySnapshot snapshot() {
        AssemblySnapshot current = snapshot;
        if (current == null) {
            throw new IllegalStateException("CORE-ASSEMBLY-002: 装配校验尚未完成");
        }
        return current;
    }

    /** 已装配 Owner Bean 与显式外部阻断快照。 */
    public record AssemblySnapshot(Map<String, String> ownerBeans,
                                   Map<String, ExternalBoundaryRegistry.BoundaryState> externalBoundaries) {
    }
}
