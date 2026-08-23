package com.jingshanghui.pos.saas.domain;

import org.junit.jupiter.api.Test;

import static com.jingshanghui.pos.saas.domain.SaasStates.*;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 状态机固定向量，覆盖合法路径与跨阶段跳转拒绝。 */
class SaasStatesTest {
    @Test void applicationTransitionsAreExplicit() {
        assertThatCode(() -> require(ApplicationState.DRAFT, ApplicationState.PREFLIGHTING)).doesNotThrowAnyException();
        assertThatCode(() -> require(ApplicationState.PREFLIGHTING, ApplicationState.READY)).doesNotThrowAnyException();
        assertThatCode(() -> require(ApplicationState.PREFLIGHTING, ApplicationState.PREFLIGHT_FAILED)).doesNotThrowAnyException();
        assertThatCode(() -> require(ApplicationState.PREFLIGHT_FAILED, ApplicationState.PREFLIGHTING)).doesNotThrowAnyException();
        assertThatCode(() -> require(ApplicationState.READY, ApplicationState.APPROVED)).doesNotThrowAnyException();
        assertThatCode(() -> require(ApplicationState.APPROVED, ApplicationState.PROVISIONING)).doesNotThrowAnyException();
        assertThatCode(() -> require(ApplicationState.PROVISIONING, ApplicationState.INITIALIZING)).doesNotThrowAnyException();
        assertThatCode(() -> require(ApplicationState.INITIALIZING, ApplicationState.ACTIVE)).doesNotThrowAnyException();
        assertThatCode(() -> require(ApplicationState.DRAFT, ApplicationState.CANCELLED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> require(ApplicationState.DRAFT, ApplicationState.ACTIVE)).hasMessageContaining("SAA-STATE-001");
        assertThatThrownBy(() -> require(ApplicationState.ACTIVE, ApplicationState.DRAFT)).hasMessageContaining("SAA-STATE-001");
    }
    @Test void entitlementTransitionsAreExplicit() {
        assertThatCode(() -> require(EntitlementState.DRAFT, EntitlementState.VALIDATING)).doesNotThrowAnyException();
        assertThatCode(() -> require(EntitlementState.VALIDATING, EntitlementState.READY)).doesNotThrowAnyException();
        assertThatCode(() -> require(EntitlementState.VALIDATING, EntitlementState.VALIDATION_FAILED)).doesNotThrowAnyException();
        assertThatCode(() -> require(EntitlementState.VALIDATION_FAILED, EntitlementState.VALIDATING)).doesNotThrowAnyException();
        assertThatCode(() -> require(EntitlementState.READY, EntitlementState.APPROVED)).doesNotThrowAnyException();
        assertThatCode(() -> require(EntitlementState.APPROVED, EntitlementState.PUBLISHED)).doesNotThrowAnyException();
        assertThatCode(() -> require(EntitlementState.PUBLISHED, EntitlementState.EFFECTIVE)).doesNotThrowAnyException();
        assertThatCode(() -> require(EntitlementState.EFFECTIVE, EntitlementState.SUSPENDED)).doesNotThrowAnyException();
        assertThatCode(() -> require(EntitlementState.SUSPENDED, EntitlementState.RETIRED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> require(EntitlementState.DRAFT, EntitlementState.EFFECTIVE)).hasMessageContaining("SAA-STATE-002");
    }
    @Test void lifecycleTransitionsPreserveHistory() {
        assertThatCode(() -> require(LifecycleState.PENDING_ACTIVATION, LifecycleState.ACTIVE)).doesNotThrowAnyException();
        assertThatCode(() -> require(LifecycleState.ACTIVE, LifecycleState.SUSPENSION_PENDING)).doesNotThrowAnyException();
        assertThatCode(() -> require(LifecycleState.SUSPENSION_PENDING, LifecycleState.SUSPENDED)).doesNotThrowAnyException();
        assertThatCode(() -> require(LifecycleState.SUSPENDED, LifecycleState.RESTORING)).doesNotThrowAnyException();
        assertThatCode(() -> require(LifecycleState.RESTORING, LifecycleState.ACTIVE)).doesNotThrowAnyException();
        assertThatCode(() -> require(LifecycleState.ACTIVE, LifecycleState.DEACTIVATION_PENDING)).doesNotThrowAnyException();
        assertThatCode(() -> require(LifecycleState.DEACTIVATION_PENDING, LifecycleState.DEACTIVATED)).doesNotThrowAnyException();
        assertThatCode(() -> require(LifecycleState.DEACTIVATED, LifecycleState.TERMINATION_REQUESTED)).doesNotThrowAnyException();
        assertThatCode(() -> require(LifecycleState.TERMINATION_REQUESTED, LifecycleState.RETENTION_HOLD)).doesNotThrowAnyException();
        assertThatCode(() -> require(LifecycleState.TERMINATION_REQUESTED, LifecycleState.TERMINATED_LOGICAL)).doesNotThrowAnyException();
        assertThatThrownBy(() -> require(LifecycleState.TERMINATED_LOGICAL, LifecycleState.ACTIVE)).hasMessageContaining("SAA-STATE-003");
    }
}
