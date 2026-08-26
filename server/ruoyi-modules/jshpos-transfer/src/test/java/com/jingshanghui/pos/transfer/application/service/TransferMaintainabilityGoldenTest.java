package com.jingshanghui.pos.transfer.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 冻结调拨公开应用 API 与事务入口，保证拆分不破坏两段式库存事实。 */
class TransferMaintainabilityGoldenTest {

    @Test
    void publicApiAndTransactionsRemainStable() {
        Set<String> expected = Set.of("approve", "cancel", "create", "detail", "dispatch", "receive",
            "reconcileTransit", "resolveDifference", "submit");
        Set<Method> methods = Arrays.stream(TransferService.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
            .collect(Collectors.toSet());
        assertThat(methods).extracting(Method::getName).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(methods).allSatisfy(method -> assertThat(method.getAnnotation(Transactional.class))
            .as(method.getName() + " transaction boundary").isNotNull());
    }
}
