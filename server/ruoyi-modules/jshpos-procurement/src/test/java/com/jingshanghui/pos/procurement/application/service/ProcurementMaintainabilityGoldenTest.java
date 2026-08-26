package com.jingshanghui.pos.procurement.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 冻结采购与补货公开应用 API 及其事务入口，防止职责拆分改变外部行为。 */
class ProcurementMaintainabilityGoldenTest {

    @Test
    void procurementPublicApiAndTransactionsRemainStable() {
        assertPublicTransactionalMethods(ProcurementService.class, Set.of(
            "approveOrder", "approveReturn", "changeSupplierState", "closeOrder", "confirmedInTransitBase",
            "confirmReceipt", "createOrder", "createReceipt", "createReplenishmentDraft", "createReturn",
            "createSupplier", "importSupplier", "orderDetail", "receiptDetail", "requireActiveSupplier",
            "submitOrder", "submitReturn"));
    }

    @Test
    void replenishmentPublicApiAndTransactionsRemainStable() {
        assertPublicTransactionalMethods(ReplenishmentService.class, Set.of(
            "approve", "createPolicy", "createPurchaseDraft", "generate", "listPolicies", "listSuggestions",
            "policyDetail", "publishPolicy", "reject", "retirePolicy", "review"));
    }

    private static void assertPublicTransactionalMethods(Class<?> type, Set<String> expected) {
        Set<Method> publicMethods = Arrays.stream(type.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> !method.isSynthetic())
            .collect(Collectors.toSet());
        assertThat(publicMethods).extracting(Method::getName).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(publicMethods).allSatisfy(method ->
            assertThat(method.getAnnotation(Transactional.class))
                .as(type.getSimpleName() + "." + method.getName() + " transaction boundary")
                .isNotNull());
    }
}
